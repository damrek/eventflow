package com.eventflow.booking.reservation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.eventflow.booking.reservation.Reservation;
import com.eventflow.booking.reservation.ReservationItem;
import com.eventflow.booking.reservation.ReservationStatus;

public record ReservationResponse(
        UUID reservationId,
        UUID eventId,
        String userId,
        ReservationStatus status,
        Instant expiresAt,
        Instant createdAt,
        List<ReservationItemResponse> items,
        BigDecimal totalAmount) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getUserId(),
                reservation.getStatus(),
                reservation.getExpiresAt(),
                reservation.getCreatedAt(),
                reservation.getItems().stream().map(ReservationItemResponse::from).toList(),
                reservation.totalAmount());
    }

    public record ReservationItemResponse(UUID ticketTypeId, int quantity, BigDecimal unitPrice) {

        static ReservationItemResponse from(ReservationItem item) {
            return new ReservationItemResponse(item.getTicketTypeId(), item.getQuantity(), item.getUnitPrice());
        }
    }
}
