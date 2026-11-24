package com.eventflow.payment.payment;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Business meter of the Payment service: {@code payment.failures}, rendered by Micrometer as
 * {@code payment_failures_total}. The only tag is the reason, with three fixed values, so the
 * series stays small enough for a dashboard.
 *
 * <p>A transient provider failure rolls its transaction back, but a counter is not transactional:
 * the increment survives and the retry increments again, so the meter counts failed attempts. That
 * is the useful reading — how often the saga meets a failing provider — and it is documented in
 * the observability doc rather than silently presented as a count of reservations.
 */
@Component
public class PaymentMetrics {

    public enum FailureReason {
        /** The provider answered "rejected": the payment is settled as failed. */
        REJECTED,
        /** The provider did not answer inside the configured read timeout. */
        TIMEOUT,
        /** Any other transient provider failure: 5xx, connection refused, open circuit. */
        ERROR
    }

    private final Counter rejected;
    private final Counter timeout;
    private final Counter error;

    /**
     * Every series is registered at startup, so a dashboard shows a zero instead of "no data"
     * until the first failure: an absent counter and an idle one are different things.
     */
    public PaymentMetrics(MeterRegistry registry) {
        this.rejected = counter(registry, FailureReason.REJECTED);
        this.timeout = counter(registry, FailureReason.TIMEOUT);
        this.error = counter(registry, FailureReason.ERROR);
    }

    public void failure(FailureReason reason) {
        switch (reason) {
            case REJECTED -> rejected.increment();
            case TIMEOUT -> timeout.increment();
            case ERROR -> error.increment();
        }
    }

    private static Counter counter(MeterRegistry registry, FailureReason reason) {
        return Counter.builder("payment.failures")
                .description("Authorizations that ended rejected, timed out or failed at the provider")
                .tag("reason", reason.name())
                .register(registry);
    }
}
