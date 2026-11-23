package com.eventflow.booking.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Wire contract of {@code EventPublished} as Booking reads it. It is deliberately a Booking-owned
 * record, not a Catalog class: the event is the contract between the services, and the consumer
 * keeps accepting the old shape when the producer adds fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventPublishedPayload(UUID eventId, String name, Instant startsAt, List<TicketType> ticketTypes) {

    /** Envelope version this consumer understands. */
    public static final int VERSION = 1;

    public List<InventoryItemSpec> toSpecs() {
        return ticketTypes.stream()
                .map(ticketType -> new InventoryItemSpec(ticketType.ticketTypeId(), ticketType.price(), ticketType.capacity()))
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TicketType(UUID ticketTypeId, String name, BigDecimal price, int capacity) {
    }
}
