package com.eventflow.notification.shared.domain;

/**
 * Base class for expected Notification failures. Each failure carries a stable machine-readable
 * code that is exposed in the RFC 9457 problem response.
 */
public abstract class NotificationException extends RuntimeException {

    private final String code;

    protected NotificationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
