package com.eventflow.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
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
 * S2/S3/S4 edge of the gate: the demo header is required, admission is driven by real capacity, a
 * token never bypasses the inventory transaction, the token TTL is a real Redis expiry (not a
 * deleted key) and an idempotent replay keeps one reservation. Complements
 * {@link WaitingRoomIntegrationTest}, which covers positions, 403 cases and the gate being off.
 */
@SpringBootTest(properties = {
        "booking.waiting-room.dispatch-interval=PT1H",
        "booking.waiting-room.batch-size=1",
        "booking.waiting-room.admission-ttl=PT1S",
        "booking.expiry.interval=PT1H",
        "eventflow.messaging.outbox.interval=PT1H",
        "spring.rabbitmq.connection-timeout=2s"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WaitingRoomAdmissionGateTest extends BookingIntegrationTest {

    private static final String USER = "gate-user-1";
    private static final String OTHER = "gate-user-2";

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

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();

    @BeforeEach
    void clearRedis() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void joinAndStatusRequireTheUserHeader() throws Exception {
        mockMvc.perform(post("/api/waiting-room/{eventId}/join", eventId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_ID_REQUIRED"));
        mockMvc.perform(get("/api/waiting-room/{eventId}/status", eventId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_ID_REQUIRED"));
    }

    @Test
    void soldOutEventAdmitsNobodyUntilAUnitReturns() throws Exception {
        provision(1);
        String reservationId = create("hold-key", null, 1).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::readTree)
                .get("reservationId").asString();
        assertThat(available()).isZero();

        join(USER);
        assertThat(dispatcher.dispatch()).doesNotContainKey(eventId);
        assertThat(redis.hasKey("waiting:" + eventId)).isTrue();
        assertThat(statusOf(USER).get("status").asString()).isEqualTo("WAITING");
        assertThat(statusOf(USER).get("position").asInt()).isEqualTo(1);

        mockMvc.perform(post("/api/reservations/{id}/cancel", reservationId).header("X-User-Id", USER))
                .andExpect(status().isOk());
        assertThat(available()).isEqualTo(1);

        assertThat(dispatcher.dispatch()).containsEntry(eventId, 1);
        assertThat(statusOf(USER).get("status").asString()).isEqualTo("ADMITTED");
    }

    @Test
    void unprovisionedEventDoesNotBreakTheDispatcher() throws Exception {
        join(USER);
        assertThat(dispatcher.dispatch()).doesNotContainKey(eventId);
        assertThat(statusOf(USER).get("status").asString()).isEqualTo("WAITING");
        assertThat(redis.hasKey("waiting:" + eventId)).isTrue();
    }

    @Test
    void anAdmittedTokenStillGoesThroughTheInventoryTransaction() throws Exception {
        provision(5);
        join(USER);
        join(OTHER);
        assertThat(dispatcher.dispatch()).containsEntry(eventId, 1);
        String token = statusOf(USER).get("token").asString();

        create("too-many", token, 6)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_TICKETS"));
        assertThat(available()).isEqualTo(5);

        create("exact", token, 5).andExpect(status().isCreated());
        assertThat(available()).isZero();
    }

    @Test
    void theAdmissionTtlIsRealAndTheRejoinGoesToTheTail() throws Exception {
        provision(5);
        join(USER);
        join(OTHER);
        assertThat(dispatcher.dispatch()).containsEntry(eventId, 1);
        String token = statusOf(USER).get("token").asString();

        await().atMost(Duration.ofSeconds(5)).ignoreExceptions().untilAsserted(() ->
                assertThat(statusOf(USER).get("status").asString()).isEqualTo("NOT_JOINED"));

        create("after-expiry", token, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"));

        JsonNode rejoin = join(USER);
        assertThat(rejoin.get("status").asString()).isEqualTo("WAITING");
        assertThat(rejoin.get("position").asInt()).isEqualTo(2);
    }

    @Test
    void idempotentReplayWithALiveTokenKeepsOneReservation() throws Exception {
        provision(5);
        join(USER);
        join(OTHER);
        assertThat(dispatcher.dispatch()).containsEntry(eventId, 1);
        String token = statusOf(USER).get("token").asString();

        String first = create("replay-key", token, 2).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = create("replay-key", token, 2).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(readTree(second).get("reservationId").asString())
                .isEqualTo(readTree(first).get("reservationId").asString());
        assertThat(available()).isEqualTo(3);
    }

    private org.springframework.test.web.servlet.ResultActions create(
            String key, String token, int quantity) throws Exception {
        var request = post("/api/reservations")
                .header("X-User-Id", USER)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ApiFixtures.reservation(eventId.toString(), general.toString(), quantity));
        if (token != null) {
            request.header("X-Admission-Token", token);
        }
        return mockMvc.perform(request);
    }

    private JsonNode join(String user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/waiting-room/{eventId}/join", eventId)
                        .header("X-User-Id", user))
                .andExpect(status().isOk()).andReturn();
        return readTree(result.getResponse().getContentAsString());
    }

    private JsonNode statusOf(String user) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/waiting-room/{eventId}/status", eventId)
                        .header("X-User-Id", user))
                .andExpect(status().isOk()).andReturn();
        return readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readTree(String json) {
        return jsonMapper.readTree(json);
    }

    private void provision(int capacity) {
        inventory.provision(eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), capacity)));
    }

    private int available() {
        return inventory.availability(eventId).stream().mapToInt(row -> row.getAvailable()).sum();
    }
}
