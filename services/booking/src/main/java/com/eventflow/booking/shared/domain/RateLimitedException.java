package com.eventflow.booking.shared.domain;

/**
 * A subject exceeded the distributed fixed-window rate limit of an endpoint. Mapped to HTTP 429
 * with a {@code Retry-After} header, so a client can back off instead of hammering the service.
 */
public class RateLimitedException extends BookingException {

    public static final String CODE = "RATE_LIMITED";

    private final long retryAfterSeconds;

    public RateLimitedException(String endpoint, long retryAfterSeconds) {
        super(CODE, "Too many requests for '" + endpoint + "'; retry in " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
