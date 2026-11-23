package com.eventflow.booking.inventory;

import java.time.Clock;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.booking.shared.messaging.EventEnvelope;
import com.eventflow.booking.shared.messaging.EventEnvelopeReader;
import com.eventflow.booking.shared.messaging.Inbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only trigger that provisions inventory: Catalog's {@code EventPublished}. The inbox claim
 * and the provisioning run in one transaction, so a duplicate delivery is a no-op and a failure
 * repeats both. The container acks only after this transaction commits.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventPublishedListener {

    private final Inbox inbox;
    private final InventoryService inventory;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @RabbitListener(queues = EventPublishedTopology.QUEUE)
    @Transactional
    public void onEventPublished(Message message) {
        EventEnvelope envelope = EventEnvelopeReader.read(jsonMapper, message);
        requireContract(envelope);
        if (!inbox.claim(EventPublishedTopology.CONSUMER, envelope.eventId(), clock.instant())) {
            log.info("Ignoring duplicate delivery of event {} to {}", envelope.eventId(), EventPublishedTopology.QUEUE);
            return;
        }
        EventPublishedPayload payload = EventEnvelopeReader.payload(
                jsonMapper, envelope, EventPublishedPayload.class);
        inventory.provision(payload.eventId(), payload.toSpecs());
        log.info("Provisioned inventory for event {} from event {}", payload.eventId(), envelope.eventId());
    }

    /**
     * A wrong type or a version this build cannot read will never become readable on retry, so it
     * is rejected immediately and lands in the DLQ instead of burning the retry budget.
     */
    private void requireContract(EventEnvelope envelope) {
        if (!EventPublishedTopology.EVENT_TYPE.equals(envelope.eventType())) {
            throw new AmqpRejectAndDontRequeueException(
                    "Unexpected event type " + envelope.eventType() + " on " + EventPublishedTopology.QUEUE);
        }
        if (envelope.eventVersion() != EventPublishedPayload.VERSION) {
            throw new AmqpRejectAndDontRequeueException("Unsupported eventVersion "
                    + envelope.eventVersion() + " for " + envelope.eventType()
                    + "; this build reads version " + EventPublishedPayload.VERSION);
        }
    }
}
