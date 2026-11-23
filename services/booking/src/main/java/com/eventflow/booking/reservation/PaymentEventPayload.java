package com.eventflow.booking.reservation;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Payload of the events Booking publishes for the payment saga. One shape serves the request,
 * the confirmation and the failure because Notification only needs the user, the reservation and
 * the event; the amount is informative and the payment never travels with card data.
 */
public record PaymentEventPayload(
        UUID reservationId,
        UUID eventId,
        String userId,
        BigDecimal amount,
        String currency,
        List<Item> items) {

    public static final String REQUESTED = "PaymentRequested";
    public static final String REQUESTED_ROUTING_KEY = "payment.requested";

    public static final String CONFIRMED = "BookingConfirmed";
    public static final String CONFIRMED_ROUTING_KEY = "booking.confirmed";

    public static final String PAYMENT_FAILED = "BookingPaymentFailed";
    public static final String PAYMENT_FAILED_ROUTING_KEY = "booking.payment-failed";

    /** Single currency of the demo; a real deployment would read it from the event. */
    public static final String CURRENCY = "EUR";

    public static PaymentEventPayload of(Reservation reservation) {
        return new PaymentEventPayload(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getUserId(),
                reservation.totalAmount(),
                CURRENCY,
                reservation.getItems().stream()
                        .map(item -> new Item(item.getTicketTypeId(), item.getQuantity()))
                        .toList());
    }

    public record Item(UUID ticketTypeId, int quantity) {
    }
}
