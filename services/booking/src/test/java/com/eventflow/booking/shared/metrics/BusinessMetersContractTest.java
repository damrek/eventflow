package com.eventflow.booking.shared.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * S2 of Phase 7: one increment per call, and no tag that could carry a user, reservation or event
 * id. A tag here would make the series grow with traffic and the dashboard unreadable.
 */
class BusinessMetersContractTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BookingMetrics metrics = new BookingMetrics(registry);

    @Test
    void everyBusinessMeterIsTaglessAndCountsOnePerCall() {
        metrics.attempt();
        metrics.attempt();
        metrics.conflict();
        metrics.confirmed();
        metrics.expired();

        assertThat(count("booking.attempts")).isEqualTo(2.0);
        assertThat(count("booking.conflicts")).isEqualTo(1.0);
        assertThat(count("booking.confirmed")).isEqualTo(1.0);
        assertThat(count("booking.expired")).isEqualTo(1.0);

        for (String name : new String[] {
                "booking.attempts", "booking.conflicts", "booking.confirmed", "booking.expired"}) {
            Counter counter = registry.find(name).counter();
            assertThat(counter).as(name).isNotNull();
            assertThat(counter.getId().getTags()).as("%s must carry no tag", name).isEmpty();
        }
    }

    private double count(String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? Double.NaN : counter.count();
    }
}
