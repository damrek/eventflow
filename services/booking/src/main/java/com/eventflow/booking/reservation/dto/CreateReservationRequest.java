package com.eventflow.booking.reservation.dto;

import java.util.List;
import java.util.UUID;

import com.eventflow.booking.reservation.TicketSelection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record CreateReservationRequest(@NotNull UUID eventId, @NotEmpty @Valid List<ReservationItemRequest> items) {

    public List<TicketSelection> toSelections() {
        return items.stream().map(ReservationItemRequest::toSelection).toList();
    }
}
