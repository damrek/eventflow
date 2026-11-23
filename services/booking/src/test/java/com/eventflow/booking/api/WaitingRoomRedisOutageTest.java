package com.eventflow.booking.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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

import com.eventflow.booking.inventory.InventoryItemSpec;
import com.eventflow.booking.inventory.InventoryService;

/**
 * Phase 5 gate: losing Redis loses the queue, never inventory. While the container is down the
 * waiting room reports 503, the admission gate and the rate limit fail open, and PostgreSQL still
 * refuses to hold more units than it has. After the restart the old queue is gone (Redis is
 * ephemeral) and the room works again.
 */
@SpringBootTest(properties = {
        "booking.waiting-room.dispatch-interval=PT1H",
        "booking.expiry.interval=PT1H",
        "eventflow.messaging.outbox.interval=PT1H",
        "spring.rabbitmq.connection-timeout=2s"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WaitingRoomRedisOutageTest extends BookingIntegrationTest {

    private static final String USER = "outage-user";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private StringRedisTemplate redis;

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
    void losesTheQueueButNeverOversells() throws Exception {
        inventory.provision(eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), 5)));
        mockMvc.perform(post("/api/waiting-room/{eventId}/join", eventId).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1));

        REDIS.stop();
        try {
            mockMvc.perform(post("/api/waiting-room/{eventId}/join", eventId).header("X-User-Id", USER))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("WAITING_ROOM_UNAVAILABLE"));

            // Gate and limiter fail open: the sale must still be decided by PostgreSQL.
            mockMvc.perform(post("/api/reservations")
                            .header("X-User-Id", USER)
                            .header("Idempotency-Key", "outage-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 1)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("HELD"));

            assertThat(inventory.availability(eventId).getFirst().getAvailable()).isEqualTo(4);
            assertThat(inventory.availability(eventId).getFirst().getHeld()).isEqualTo(1);
        } finally {
            REDIS.start();
        }

        await().atMost(60, SECONDS).ignoreExceptions().untilAsserted(() ->
                mockMvc.perform(post("/api/waiting-room/{eventId}/join", eventId).header("X-User-Id", "after-restart"))
                        .andExpect(status().isOk()));

        // The queue was ephemeral: the user that joined before the outage has no position any more.
        mockMvc.perform(get("/api/waiting-room/{eventId}/status", eventId).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_JOINED"));

        assertThat(inventory.availability(eventId).getFirst().getAvailable()
                + inventory.availability(eventId).getFirst().getHeld()
                + inventory.availability(eventId).getFirst().getSold()).isEqualTo(5);
    }
}
