package com.eventflow.booking.reservation.dto;

import java.util.UUID;

import com.eventflow.booking.reservation.TicketSelection;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReservationItemRequest(@NotNull UUID ticketTypeId, @Min(value = 1, message = "must be at least 1") int quantity) {

    public TicketSelection toSelection() {
        return new TicketSelection(ticketTypeId, quantity);
    }
}
