package com.eventflow.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** S2: three reason values, registered before the first failure, and nothing else in the tag set. */
class PaymentMetricsContractTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentMetrics metrics = new PaymentMetrics(registry);

    @Test
    void registersTheThreeReasonsAtStartupWithNoOtherTag() {
        Map<String, Double> series = new TreeMap<>();
        for (Counter counter : registry.find("payment.failures").counters()) {
            assertThat(counter.getId().getTags()).hasSize(1);
            assertThat(counter.getId().getTag("reason")).isIn("REJECTED", "TIMEOUT", "ERROR");
            series.put(counter.getId().getTag("reason"), counter.count());
        }
        assertThat(series).containsOnlyKeys("ERROR", "REJECTED", "TIMEOUT");
        assertThat(series.values()).containsOnly(0.0);
    }

    @Test
    void countsTheRejectedAttemptOnly() {
        metrics.failure(PaymentMetrics.FailureReason.REJECTED);
        assertThat(registry.get("payment.failures").tag("reason", "REJECTED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("payment.failures").tag("reason", "TIMEOUT").counter().count()).isZero();
        assertThat(registry.get("payment.failures").tag("reason", "ERROR").counter().count()).isZero();
    }
}
