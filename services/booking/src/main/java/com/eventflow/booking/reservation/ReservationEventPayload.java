package com.eventflow.booking.reservation;

import java.util.List;
import java.util.UUID;

/**
 * Payload of the reservation lifecycle events Booking publishes. Notification is the consumer and
 * only needs to know which user, which reservation and which units; no price or personal data
 * travels over the wire.
 */
public record ReservationEventPayload(UUID reservationId, UUID eventId, String userId, List<Item> items) {

    public static final String CANCELLED = "BookingCancelled";
    public static final String CANCELLED_ROUTING_KEY = "booking.cancelled";
    public static final String EXPIRED = "BookingExpired";
    public static final String EXPIRED_ROUTING_KEY = "booking.expired";

    public static ReservationEventPayload from(Reservation reservation) {
        return new ReservationEventPayload(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getUserId(),
                reservation.getItems().stream()
                        .map(item -> new Item(item.getTicketTypeId(), item.getQuantity()))
                        .toList());
    }

    public record Item(UUID ticketTypeId, int quantity) {
    }
}
