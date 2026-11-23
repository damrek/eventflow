package com.eventflow.payment.provider;

/**
 * Transient provider failure: timeout, {@code 5xx}, connection refused or an open circuit. It is
 * deliberately unchecked so the consumer transaction rolls back, the inbox with it, and the
 * broker retry queues try again instead of finalizing a payment nobody answered.
 */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
