package com.eventflow.catalog.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code EventPublished}. Booking is the consumer and needs exactly what is required
 * to provision inventory: the event identity and one entry per ticket type. No venue, address or
 * other catalog detail travels over the wire.
 */
public record EventPublishedPayload(UUID eventId, String name, Instant startsAt, List<TicketTypePayload> ticketTypes) {

    /** Routing key of {@code EventPublished} on the {@code eventflow.events} exchange. */
    public static final String ROUTING_KEY = "event.published";
    public static final String EVENT_TYPE = "EventPublished";

    public static EventPublishedPayload from(Event event) {
        return new EventPublishedPayload(
                event.getId(),
                event.getName(),
                event.getStartsAt(),
                event.getTicketTypes().stream().map(TicketTypePayload::from).toList());
    }

    public record TicketTypePayload(UUID ticketTypeId, String name, BigDecimal price, int capacity) {

        static TicketTypePayload from(TicketType ticketType) {
            return new TicketTypePayload(
                    ticketType.getId(), ticketType.getName(), ticketType.getPrice(), ticketType.getInitialCapacity());
        }
    }
}
