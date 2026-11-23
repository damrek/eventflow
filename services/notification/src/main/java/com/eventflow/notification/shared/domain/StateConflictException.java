package com.eventflow.notification.shared.domain;

/**
 * A request that conflicts with the current state of an aggregate or with a previously used
 * idempotency key. Mapped to HTTP 409.
 */
public class StateConflictException extends NotificationException {

    public StateConflictException(String code, String message) {
        super(code, message);
    }
}
