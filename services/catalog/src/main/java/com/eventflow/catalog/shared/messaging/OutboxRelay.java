package com.eventflow.catalog.shared.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.catalog.shared.web.TraceId;

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

    private final OutboxEventRepository outbox;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;
    private final Clock clock;

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
                    operations.send(properties.exchange(), event.getRoutingKey(), message(event));
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
