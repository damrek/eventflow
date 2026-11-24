package com.eventflow.booking.shared.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Business meters of the Booking service, deliberately few and free of high-cardinality tags: a
 * dashboard may break a series down by outcome, never by user, reservation or event.
 *
 * <p>Micrometer renders {@code booking.attempts} as {@code booking_attempts_total} in Prometheus,
 * which is the name the observability document promises. A counter lives in memory, not in the
 * transaction: an attempt that rolls back (a conflict, a rejected hold) still happened, and the
 * meter keeps it.
 */
@Component
public class BookingMetrics {

    private final Counter attempts;
    private final Counter confirmed;
    private final Counter conflicts;
    private final Counter expired;

    public BookingMetrics(MeterRegistry registry) {
        this.attempts = Counter.builder("booking.attempts")
                .description("Reservation creations that claimed a new idempotency key")
                .register(registry);
        this.confirmed = Counter.builder("booking.confirmed")
                .description("Reservations confirmed after an authorized payment")
                .register(registry);
        this.conflicts = Counter.builder("booking.conflicts")
                .description("Reservation creations refused because the stock could not be held")
                .register(registry);
        this.expired = Counter.builder("booking.expired")
                .description("Reservations expired by the expiry worker")
                .register(registry);
    }

    /** A creation attempt that reached the hold step; idempotent replays do not count. */
    public void attempt() {
        attempts.increment();
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void conflict() {
        conflicts.increment();
    }

    public void expired() {
        expired.increment();
    }
}
