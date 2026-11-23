package com.eventflow.payment.payment;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Compensation command published by Booking when an authorization arrives for a reservation that
 * can no longer be confirmed (expired, cancelled or already failed). {@code reason} is for the
 * audit trail, not for the user.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundRequestedPayload(
        UUID paymentId, UUID reservationId, BigDecimal amount, String currency, String reason) {

    /** Envelope version this consumer understands. */
    public static final int VERSION = 1;
}
