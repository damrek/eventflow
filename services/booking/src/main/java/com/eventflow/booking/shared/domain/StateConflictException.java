package com.eventflow.booking.shared.domain;

/**
 * A request that conflicts with the current state of an aggregate or with a previously used
 * idempotency key. Mapped to HTTP 409.
 */
public class StateConflictException extends BookingException {

    public StateConflictException(String code, String message) {
        super(code, message);
    }
}
