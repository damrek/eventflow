package com.eventflow.booking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reservation timing knobs. Defaults match {@code application.yml} so a missing property never
 * turns a hold into a zero-length or infinite one.
 */
@ConfigurationProperties("booking")
public record BookingProperties(Reservation reservation, Expiry expiry) {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
    private static final int DEFAULT_BATCH_SIZE = 50;

    public BookingProperties {
        reservation = reservation == null ? new Reservation(DEFAULT_TTL) : reservation;
        expiry = expiry == null ? new Expiry(DEFAULT_INTERVAL, DEFAULT_BATCH_SIZE) : expiry;
    }

    public record Reservation(Duration ttl) {

        public Reservation {
            if (ttl == null || ttl.isNegative() || ttl.isZero()) {
                ttl = DEFAULT_TTL;
            }
        }
    }

    public record Expiry(Duration interval, int batchSize) {

        public Expiry {
            if (interval == null || interval.isNegative() || interval.isZero()) {
                interval = DEFAULT_INTERVAL;
            }
            if (batchSize < 1) {
                batchSize = DEFAULT_BATCH_SIZE;
            }
        }
    }
}
