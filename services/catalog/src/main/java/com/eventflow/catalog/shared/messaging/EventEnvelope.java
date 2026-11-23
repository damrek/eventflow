package com.eventflow.catalog.shared.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned wire format of {@code docs/03-apis-eventos.md}. The outbox stores the serialized
 * envelope, so a consumer always reads the schema the producer had when the business change
 * happened, and an explicit {@code eventVersion} lets a consumer refuse what it cannot read.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID aggregateId,
        UUID correlationId,
        Object payload) {

    public static EventEnvelope of(
            String eventType, int eventVersion, Instant occurredAt, UUID aggregateId, UUID correlationId,
            Object payload) {
        return new EventEnvelope(
                UUID.randomUUID(), eventType, eventVersion, occurredAt, aggregateId, correlationId, payload);
    }
}
