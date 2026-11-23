package com.eventflow.booking.shared.messaging;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Versioned wire format of {@code docs/03-apis-eventos.md}. The payload stays a tree because the
 * consumer validates {@code eventVersion} before mapping it to its own contract type.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID aggregateId,
        UUID correlationId,
        JsonNode payload) {
}
