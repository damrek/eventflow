package com.eventflow.payment.shared.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.payment.shared.web.TraceId;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains the outbox to the topic exchange and marks rows published only after the broker
 * confirmed them. A broker outage is therefore a delay, not a loss: rows stay unpublished and
 * the next tick retries. The transaction is rolled back on an unconfirmed publish, so no row is
 * ever marked as sent without an ack.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private static final String TRACEPARENT = "traceparent";

    private final OutboxEventRepository outbox;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;
    private final Clock clock;
    private final Tracer tracer;
    private final Propagator propagator;

    @Scheduled(fixedDelayString = "${eventflow.messaging.outbox.interval}")
    @Transactional
    public int relay() {
        List<OutboxEvent> batch = outbox.lockUnpublished(properties.outbox().batchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            rabbitTemplate.invoke(operations -> {
                for (OutboxEvent event : batch) {
                    // The relay span continues the trace that wrote the row, instead of starting
                    // one in the relay thread, so the broker hop stays inside the same trace.
                    Span relaySpan = restore(event.getTraceparent());
                    try (Tracer.SpanInScope scope = relaySpan == null ? null : tracer.withSpan(relaySpan)) {
                        operations.send(properties.exchange(), event.getRoutingKey(), message(event));
                    } finally {
                        if (relaySpan != null) {
                            relaySpan.end();
                        }
                    }
                }
                operations.waitForConfirmsOrDie(properties.outbox().confirmTimeout().toMillis());
                return null;
            });
        } catch (AmqpException notPublished) {
            log.warn("Broker did not confirm {} outbox event(s); they stay pending [traceId={}]",
                    batch.size(), TraceId.current(), notPublished);
            return 0;
        }
        Instant now = clock.instant();
        batch.forEach(event -> event.markPublished(now));
        log.info("Published {} outbox event(s)", batch.size());
        return batch.size();
    }

    /**
     * Rebuilds the parent context stored with the row. A row written by a scheduled job has no
     * traceparent and publishes under the relay's own span, exactly as before.
     */
    private Span restore(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        return propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get)
                .name("outbox relay")
                .start();
    }

    private static Message message(OutboxEvent event) {
        return MessageBuilder.withBody(event.getBody().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.getEventId().toString())
                .setTimestamp(Date.from(event.getOccurredAt()))
                .setType(event.getEventType())
                .build();
    }
}
