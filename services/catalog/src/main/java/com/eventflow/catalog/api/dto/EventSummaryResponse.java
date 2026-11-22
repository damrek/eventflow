package com.eventflow.catalog.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.eventflow.catalog.domain.EventStatus;

public record EventSummaryResponse(
        UUID id,
        String name,
        UUID venueId,
        String venueName,
        Instant startsAt,
        EventStatus status) {
}
