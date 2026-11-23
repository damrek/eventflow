package com.eventflow.payment.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payload of {@code PaymentAuthorized} and {@code PaymentRejected}. Both carry the payment id so
 * Booking can record it and, for a late authorization, publish the refund compensation.
 */
public record PaymentResultPayload(
        UUID paymentId,
        UUID reservationId,
        UUID eventId,
        String userId,
        BigDecimal amount,
        String currency,
        String providerReference,
        String reason) {

    public static PaymentResultPayload authorized(Payment payment) {
        return new PaymentResultPayload(
                payment.getId(),
                payment.getReservationId(),
                payment.getEventId(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getProviderReference(),
                null);
    }

    public static PaymentResultPayload rejected(Payment payment) {
        return new PaymentResultPayload(
                payment.getId(),
                payment.getReservationId(),
                payment.getEventId(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency(),
                null,
                payment.getRejectionReason());
    }
}
