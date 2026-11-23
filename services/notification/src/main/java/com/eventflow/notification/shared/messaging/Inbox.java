package com.eventflow.notification.shared.messaging;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Inbox of processed events. The insert runs in the consumer transaction, so the business change
 * and the "already handled" marker commit or roll back together: an at-least-once redelivery
 * finds the marker and does nothing, and a rollback lets the message be retried.
 */
@Component
@RequiredArgsConstructor
public class Inbox {

    private final JdbcTemplate jdbc;

    /** @return {@code true} when this delivery is the first one for the consumer. */
    public boolean claim(String consumerName, UUID eventId, Instant processedAt) {
        int inserted = jdbc.update(
                """
                insert into processed_message (consumer_name, event_id, processed_at)
                values (?, ?, ?)
                on conflict (consumer_name, event_id) do nothing
                """,
                consumerName,
                eventId,
                OffsetDateTime.ofInstant(processedAt, ZoneOffset.UTC));
        return inserted == 1;
    }
}
