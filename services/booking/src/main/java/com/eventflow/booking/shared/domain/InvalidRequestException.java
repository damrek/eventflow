package com.eventflow.booking.shared.domain;

/**
 * A request that is well-formed JSON but missing or misusing a protocol-level input
 * (headers, identifiers). Mapped to HTTP 400.
 */
public class InvalidRequestException extends BookingException {

    public InvalidRequestException(String code, String message) {
        super(code, message);
    }
}
