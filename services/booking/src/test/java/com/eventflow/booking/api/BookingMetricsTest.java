package com.eventflow.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import com.eventflow.booking.inventory.InventoryItemSpec;
import com.eventflow.booking.inventory.InventoryService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Pins the semantics of the Booking business meters, not their spelling: the Prometheus names are
 * asserted by {@link MetricsEndpointTest}. Counters live for the whole JVM and the Spring context is
 * shared, so every assertion is a delta around the call under test.
 */
@SpringBootTest(properties = {
        "booking.expiry.interval=PT1H",
        "eventflow.messaging.outbox.interval=200ms",
        "eventflow.messaging.retry.delays=200ms,200ms,200ms",
        "spring.rabbitmq.connection-timeout=2s"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BookingMetricsTest extends BookingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private MeterRegistry registry;

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();

    @Test
    void countsAnAttemptPerNewCreationAndNeverAReplay() throws Exception {
        provision(10);
        double attempts = count("booking.attempts");

        create("metrics-key", 1).andExpect(status().isCreated());
        assertThat(count("booking.attempts")).isEqualTo(attempts + 1);

        create("metrics-key", 1).andExpect(status().isCreated());
        assertThat(count("booking.attempts"))
                .as("an idempotent replay is a cached response, not a new attempt")
                .isEqualTo(attempts + 1);
    }

    @Test
    void countsAConflictAndTheAttemptThatLedToIt() throws Exception {
        provision(1);
        double attempts = count("booking.attempts");
        double conflicts = count("booking.conflicts");

        create("conflict-key", 2).andExpect(status().isUnprocessableContent());

        assertThat(count("booking.attempts")).isEqualTo(attempts + 1);
        assertThat(count("booking.conflicts")).isEqualTo(conflicts + 1);
    }

    private org.springframework.test.web.servlet.ResultActions create(String key, int quantity) throws Exception {
        return mockMvc.perform(post("/api/reservations")
                .header("X-User-Id", ApiFixtures.USER)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ApiFixtures.reservation(eventId.toString(), general.toString(), quantity)));
    }

    private void provision(int capacity) {
        inventory.provision(eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), capacity)));
    }

    private double count(String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }
}
