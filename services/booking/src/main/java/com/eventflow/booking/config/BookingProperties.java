package com.eventflow.booking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reservation timing and Redis throttle knobs. Defaults match {@code application.yml} so a
 * missing property never turns a hold into a zero-length or infinite one, disables the waiting
 * room dispatcher, or removes the rate limit.
 */
@ConfigurationProperties("booking")
public record BookingProperties(
        Reservation reservation, Expiry expiry, WaitingRoom waitingRoom, RateLimit rateLimit) {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final Duration DEFAULT_DISPATCH_INTERVAL = Duration.ofSeconds(2);
    private static final int DEFAULT_ADMISSION_BATCH = 25;
    private static final Duration DEFAULT_ADMISSION_TTL = Duration.ofMinutes(2);
    private static final Duration DEFAULT_RATE_WINDOW = Duration.ofMinutes(1);
    private static final int DEFAULT_JOIN_MAX = 60;
    private static final int DEFAULT_RESERVATION_MAX = 300;

    public BookingProperties {
        reservation = reservation == null ? new Reservation(DEFAULT_TTL) : reservation;
        expiry = expiry == null ? new Expiry(DEFAULT_INTERVAL, DEFAULT_BATCH_SIZE) : expiry;
        waitingRoom = waitingRoom == null ? new WaitingRoom(null, 0, null) : waitingRoom;
        rateLimit = rateLimit == null ? new RateLimit(null, 0, 0) : rateLimit;
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

    /** Dispatch cadence, admitted members per event and how long an admission token is valid. */
    public record WaitingRoom(Duration dispatchInterval, int batchSize, Duration admissionTtl) {

        public WaitingRoom {
            if (dispatchInterval == null || dispatchInterval.isNegative() || dispatchInterval.isZero()) {
                dispatchInterval = DEFAULT_DISPATCH_INTERVAL;
            }
            if (batchSize < 1) {
                batchSize = DEFAULT_ADMISSION_BATCH;
            }
            if (admissionTtl == null || admissionTtl.isNegative() || admissionTtl.isZero()) {
                admissionTtl = DEFAULT_ADMISSION_TTL;
            }
        }
    }

    /** One fixed window per subject and endpoint, shared by every replica through Redis. */
    public record RateLimit(Duration window, int joinMax, int reservationMax) {

        public RateLimit {
            if (window == null || window.isNegative() || window.isZero()) {
                window = DEFAULT_RATE_WINDOW;
            }
            if (joinMax < 1) {
                joinMax = DEFAULT_JOIN_MAX;
            }
            if (reservationMax < 1) {
                reservationMax = DEFAULT_RESERVATION_MAX;
            }
        }
    }
}
