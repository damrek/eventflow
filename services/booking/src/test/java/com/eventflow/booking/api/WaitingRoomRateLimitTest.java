package com.eventflow.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * Phase 5 gate for the distributed limit: the window lives only in Redis, so the HTTP contract is
 * the same for one replica or many (the two-replica proof is {@link RateLimitSharedStateTest}).
 */
@SpringBootTest(properties = {
        "booking.rate-limit.join-max=2",
        "booking.rate-limit.reservation-max=1",
        "booking.rate-limit.window=PT1M",
        "booking.waiting-room.dispatch-interval=PT1H",
        "booking.expiry.interval=PT1H",
        "eventflow.messaging.outbox.interval=PT1H",
        "spring.rabbitmq.connection-timeout=2s"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WaitingRoomRateLimitTest extends BookingIntegrationTest {

    private static final String USER = "limited-user";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private StringRedisTemplate redis;

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();

    @BeforeEach
    void clearRateLimitCounters() {
        Set<String> keys = redis.keys("rate:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void joinReturns429WithRetryAfterWhenTheWindowIsExhausted() throws Exception {
        join().andExpect(status().isOk());
        join().andExpect(status().isOk());
        join()
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Retry-After", greaterThanOrEqualTo("1")))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    @Test
    void reservationCreationIsLimitedPerUser() throws Exception {
        inventory.provision(eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), 10)));

        reservation("key-1").andExpect(status().isCreated());
        reservation("key-2")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(inventory.availability(eventId).getFirst().getAvailable()).isEqualTo(9);
    }

    private org.springframework.test.web.servlet.ResultActions join() throws Exception {
        return mockMvc.perform(post("/api/waiting-room/{eventId}/join", eventId).header("X-User-Id", USER));
    }

    private org.springframework.test.web.servlet.ResultActions reservation(String key) throws Exception {
        return mockMvc.perform(post("/api/reservations")
                .header("X-User-Id", USER)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 1)));
    }
}
