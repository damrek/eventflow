package com.eventflow.booking.reservation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Compensation command Booking publishes when an authorization arrives for a reservation it can
 * no longer confirm. Payment owns the effect (refund); Booking only states which payment and why.
 */
public record RefundRequestPayload(
        UUID paymentId, UUID reservationId, BigDecimal amount, String currency, String reason) {

    public static final String EVENT_TYPE = "PaymentRefundRequested";
    public static final String ROUTING_KEY = "payment.refund-requested";
}
