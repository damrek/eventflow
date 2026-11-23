package com.eventflow.booking.shared.domain;

/**
 * Base class for expected Booking failures. Each failure carries a stable machine-readable
 * code that is exposed in the RFC 9457 problem response.
 */
public abstract class BookingException extends RuntimeException {

    private final String code;

    protected BookingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
