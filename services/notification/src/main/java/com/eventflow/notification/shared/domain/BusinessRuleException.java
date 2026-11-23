package com.eventflow.notification.shared.domain;

/**
 * A well-formed request that violates a domain invariant. Mapped to HTTP 422.
 */
public class BusinessRuleException extends NotificationException {

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }
}
