package com.eventflow.booking.shared.messaging;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.eventflow.booking.shared.web.TraceId;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes events to the outbox. Callers invoke it inside the transaction that performs the
 * business change, so "the event happened" and "the event is durable" are the same commit.
 */
@Component
@RequiredArgsConstructor
public class EventPublisher {

    /** Version of every envelope this service produces; a breaking payload change bumps it. */
    public static final int SCHEMA_VERSION = 1;

    private final OutboxEventRepository outbox;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OutboxEvent publish(String eventType, String routingKey, UUID aggregateId, Object payload) {
        JsonNode payloadTree = jsonMapper.valueToTree(payload);
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), eventType, SCHEMA_VERSION, clock.instant(), aggregateId, correlationId(),
                payloadTree);
        return outbox.save(OutboxEvent.pending(envelope, routingKey, jsonMapper.writeValueAsString(envelope)));
    }

    /**
     * Reuses the request correlation id when the trigger was an HTTP call, so a log line in the
     * consumer can be traced back to the request that started the flow.
     */
    private UUID correlationId() {
        try {
            return UUID.fromString(TraceId.current());
        } catch (IllegalArgumentException notACorrelationId) {
            return UUID.randomUUID();
        }
    }
}
