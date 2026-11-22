package com.eventflow.catalog.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.eventflow.catalog.domain.EventStatus;

public record EventDetailResponse(
        UUID id,
        String name,
        UUID venueId,
        String venueName,
        Instant startsAt,
        EventStatus status,
        int totalCapacity,
        List<TicketTypeResponse> ticketTypes) {
}
