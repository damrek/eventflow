package com.eventflow.booking.shared.domain;

/**
 * A well-formed request that violates a domain invariant. Mapped to HTTP 422.
 */
public class BusinessRuleException extends BookingException {

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }
}
