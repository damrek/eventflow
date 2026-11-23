package com.eventflow.booking.inventory.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.eventflow.booking.inventory.EventInventory;

public record AvailabilityResponse(UUID eventId, List<TicketTypeAvailabilityResponse> ticketTypes) {

    public static AvailabilityResponse from(UUID eventId, List<EventInventory> rows) {
        return new AvailabilityResponse(
                eventId, rows.stream().map(TicketTypeAvailabilityResponse::from).toList());
    }

    public record TicketTypeAvailabilityResponse(
            UUID ticketTypeId, BigDecimal price, int capacity, int available, int held, int sold) {

        static TicketTypeAvailabilityResponse from(EventInventory inventory) {
            return new TicketTypeAvailabilityResponse(
                    inventory.getTicketTypeId(),
                    inventory.getPrice(),
                    inventory.getCapacity(),
                    inventory.getAvailable(),
                    inventory.getHeld(),
                    inventory.getSold());
        }
    }
}
