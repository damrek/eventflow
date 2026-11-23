package com.eventflow.booking.reservation;

import com.eventflow.booking.shared.domain.InvalidRequestException;

/**
 * {@code Idempotency-Key} header value. Required for reservation creation: it is the only way a
 * client can retry a request after a timeout without guessing whether the first one succeeded.
 */
public record IdempotencyKey(String value) {

    private static final int MAX_LENGTH = 200;

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
        }
        value = value.trim();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidRequestException(
                    "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key must be at most " + MAX_LENGTH + " characters");
        }
    }
}
