package com.eventflow.catalog.event.dto;

import java.time.Instant;
import java.util.UUID;

import com.eventflow.catalog.event.Event;
import com.eventflow.catalog.event.EventStatus;

public record EventSummaryResponse(
        UUID id,
        String name,
        UUID venueId,
        String venueName,
        Instant startsAt,
        EventStatus status) {

    public static EventSummaryResponse from(Event event) {
        return new EventSummaryResponse(
                event.getId(),
                event.getName(),
                event.getVenue().getId(),
                event.getVenue().getName(),
                event.getStartsAt(),
                event.getStatus());
    }
}
