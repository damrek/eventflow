package com.eventflow.booking.reservation;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Wire contract of {@code PaymentAuthorized} and {@code PaymentRejected} as Booking reads them.
 * The reason is only logged for a rejection; the payment id is what a late authorization needs to
 * ask for its refund.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentResultPayload(
        UUID paymentId,
        UUID reservationId,
        UUID eventId,
        String userId,
        BigDecimal amount,
        String currency,
        String providerReference,
        String reason) {

    /** Envelope version this consumer understands. */
    public static final int VERSION = 1;
}
