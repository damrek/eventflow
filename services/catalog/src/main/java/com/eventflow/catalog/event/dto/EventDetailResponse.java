package com.eventflow.catalog.event.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.eventflow.catalog.event.Event;
import com.eventflow.catalog.event.EventStatus;

public record EventDetailResponse(
        UUID id,
        String name,
        UUID venueId,
        String venueName,
        Instant startsAt,
        EventStatus status,
        int totalCapacity,
        List<TicketTypeResponse> ticketTypes) {

    public static EventDetailResponse from(Event event) {
        return new EventDetailResponse(
                event.getId(),
                event.getName(),
                event.getVenue().getId(),
                event.getVenue().getName(),
                event.getStartsAt(),
                event.getStatus(),
                event.totalCapacity(),
                event.getTicketTypes().stream().map(TicketTypeResponse::from).toList());
    }
}
