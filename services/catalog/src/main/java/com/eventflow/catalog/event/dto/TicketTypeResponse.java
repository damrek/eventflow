package com.eventflow.catalog.event.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.eventflow.catalog.event.TicketType;

public record TicketTypeResponse(UUID id, String name, BigDecimal price, int capacity) {

    public static TicketTypeResponse from(TicketType ticketType) {
        return new TicketTypeResponse(
                ticketType.getId(), ticketType.getName(), ticketType.getPrice(), ticketType.getInitialCapacity());
    }
}
