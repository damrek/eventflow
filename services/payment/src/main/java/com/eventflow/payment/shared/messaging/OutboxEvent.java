package com.eventflow.payment.shared.messaging;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A message waiting to be published, written in the same transaction as the business change it
 * describes (Transactional Outbox). {@code body} is the serialized {@link EventEnvelope} and is
 * sent verbatim, so what the consumer reads is exactly what the producer committed.
 *
 * <p>{@code published_at} is set only after the broker confirmed the publish. A crash or outage
 * in between leaves the row unpublished and the next relay tick publishes it again: at-least-once
 * by design, which is why consumers keep an inbox.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 100)
    private String routingKey;

    @Column(name = "body", nullable = false)
    private String body;

    /** W3C {@code traceparent} of the span that wrote the row; null when there was no span. */
    @Column(name = "traceparent", length = 100)
    private String traceparent;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public static OutboxEvent pending(
            EventEnvelope envelope, String routingKey, String body, String traceparent) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID();
        event.eventId = envelope.eventId();
        event.aggregateId = envelope.aggregateId();
        event.eventType = envelope.eventType();
        event.routingKey = routingKey;
        event.body = body;
        event.traceparent = traceparent;
        event.occurredAt = envelope.occurredAt();
        return event;
    }

    public void markPublished(Instant now) {
        this.publishedAt = now;
    }
}
