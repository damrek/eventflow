package com.eventflow.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.eventflow.booking.inventory.InventoryItemSpec;
import com.eventflow.booking.inventory.InventoryService;
import com.eventflow.booking.waitingroom.WaitingRoomDispatcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 5 gate: a user joins, sees a server-assigned position and is admitted in a batch; the
 * admission token is what allows a reservation while the queue is active; an expired or foreign
 * token cannot skip the wait; and the gate is off once no queue exists. The scheduler is disabled
 * so the dispatcher runs only when the test calls it.
 */
@SpringBootTest(properties = {
        "booking.waiting-room.dispatch-interval=PT1H",
        "booking.waiting-room.batch-size=1",
        "booking.waiting-room.admission-ttl=PT2M",
        "booking.expiry.interval=PT1H",
        "eventflow.messaging.outbox.interval=PT1H",
        "spring.rabbitmq.connection-timeout=2s"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WaitingRoomIntegrationTest extends BookingIntegrationTest {

    private static final String USER = "waiting-user-1";
    private static final String OTHER_USER = "waiting-user-2";
    private static final String THIRD_USER = "waiting-user-3";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private WaitingRoomDispatcher dispatcher;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry registry;

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();

    @BeforeEach
    void clearWaitingRoomState() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void joinAssignsAStableServerSidePosition() throws Exception {
        assertThat(join(USER).get("position").asInt()).isEqualTo(1);
        assertThat(join(OTHER_USER).get("position").asInt()).isEqualTo(2);

        JsonNode rejoin = join(USER);
        assertThat(rejoin.get("status").asString()).isEqualTo("WAITING");
        assertThat(rejoin.get("position").asInt()).isEqualTo(1);
        assertThat(rejoin.get("waitingCount").asInt()).isEqualTo(2);
        assertThat(rejoin.get("token").isNull()).isTrue();

        mockMvc.perform(get("/api/waiting-room/{eventId}/status", eventId).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.waitingCount").value(2));

        mockMvc.perform(get("/api/waiting-room/{eventId}/status", eventId).header("X-User-Id", "never-joined"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_JOINED"))
                .andExpect(jsonPath("$.position").value(nullValue()))
                .andExpect(jsonPath("$.waitingCount").value(2));
    }

    @Test
    void dispatcherAdmitsInBatchesAndIssuesAnExpiringToken() throws Exception {
        provision(10);
        join(USER);
        join(OTHER_USER);
        join(THIRD_USER);

        assertThat(dispatcher.dispatch()).containsEntry(eventId, 1);

        JsonNode admitted = statusOf(USER);
        assertThat(admitted.get("status").asString()).isEqualTo("ADMITTED");
        assertThat(admitted.get("token").asString()).isNotBlank();
        assertThat(admitted.get("expiresAt").asString()).isNotBlank();
        assertThat(admitted.get("position").isNull()).isTrue();

        assertThat(statusOf(OTHER_USER).get("position").asInt()).isEqualTo(1);
        assertThat(statusOf(OTHER_USER).get("waitingCount").asInt()).isEqualTo(2);

        assertThat(dispatcher.dispatch()).containsEntry(eventId, 1);
        assertThat(dispatcher.dispatch()).containsEntry(eventId, 1);
        assertThat(dispatcher.dispatch()).doesNotContainKey(eventId);

        assertThat(redis.hasKey("waiting:" + eventId)).isFalse();
        assertThat(redis.opsForSet().members("waiting-events")).isEmpty();
        assertThat(statusOf(THIRD_USER).get("status").asString()).isEqualTo("ADMITTED");
    }

    @Test
    void reservationRequiresATokenWhileTheQueueIsActive() throws Exception {
        provision(5);
        join(USER);
        join(OTHER_USER);

        createReservation(USER, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"));

        // Admit only the first waiter: the queue still exists, so the gate stays on.
        assertThat(dispatcher.dispatch()).containsEntry(eventId, 1);
        String token = statusOf(USER).get("token").asString();

        createReservation(USER, "forged-token")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"));
        createReservation(OTHER_USER, token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"));

        createReservation(USER, token).andExpect(status().isCreated());
        assertThat(available()).isEqualTo(4);
    }

    @Test
    void anExpiredTokenCannotSkipTheWait() throws Exception {
        provision(5);
        join(USER);
        join(OTHER_USER);
        assertThat(dispatcher.dispatch()).containsEntry(eventId, 1);
        String expiredToken = statusOf(USER).get("token").asString();

        // Expiry is the key disappearing; deleting it makes the race deterministic.
        redis.delete("waiting-token:" + eventId + ":" + USER);

        assertThat(statusOf(USER).get("status").asString()).isEqualTo("NOT_JOINED");
        createReservation(USER, expiredToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"));

        JsonNode rejoin = join(USER);
        assertThat(rejoin.get("status").asString()).isEqualTo("WAITING");
        assertThat(rejoin.get("position").asInt()).isEqualTo(2);
    }

    @Test
    void exposesTheQueuedUsersGauge() throws Exception {
        provision(5);
        assertThat(queuedUsers()).isZero();

        join(USER);
        join(OTHER_USER);
        assertThat(queuedUsers()).isEqualTo(2);

        dispatcher.dispatch();
        assertThat(queuedUsers())
                .as("this class admits one user per tick, so an admitted user leaves the queue")
                .isEqualTo(1);
    }

    @Test
    void gateIsOffWhenTheEventHasNoQueue() throws Exception {
        provision(5);

        createReservation(USER, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"));
        assertThat(available()).isEqualTo(4);
    }

    private double queuedUsers() {
        return registry.find("waiting.room.users").gauge().value();
    }

    private JsonNode join(String user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/waiting-room/{eventId}/join", eventId)
                        .header("X-User-Id", user))
                .andExpect(status().isOk())
                .andReturn();
        return jsonMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode statusOf(String user) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/waiting-room/{eventId}/status", eventId)
                        .header("X-User-Id", user))
                .andExpect(status().isOk())
                .andReturn();
        return jsonMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private org.springframework.test.web.servlet.ResultActions createReservation(String user, String token)
            throws Exception {
        var request = post("/api/reservations")
                .header("X-User-Id", user)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 1));
        if (token != null) {
            request.header("X-Admission-Token", token);
        }
        return mockMvc.perform(request);
    }

    private void provision(int capacity) {
        inventory.provision(eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), capacity)));
    }

    private int available() {
        return inventory.availability(eventId).stream()
                .mapToInt(row -> row.getAvailable())
                .sum();
    }
}
