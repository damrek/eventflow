package com.eventflow.notification.notification;

import java.time.Clock;
import java.util.UUID;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.notification.shared.messaging.EventEnvelope;
import com.eventflow.notification.shared.messaging.EventEnvelopeReader;
import com.eventflow.notification.shared.messaging.Inbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns booking events into mailbox entries. The inbox claim and the notification insert share
 * one transaction, so a redelivery writes nothing twice and a rollback retries the whole effect.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingEventListener {

    private final Inbox inbox;
    private final NotificationService notifications;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @RabbitListener(queues = BookingEventsTopology.QUEUE)
    @Transactional
    public void onBookingEvent(Message message) {
        EventEnvelope envelope = EventEnvelopeReader.read(jsonMapper, message);
        NotificationType type = NotificationType.fromEventType(envelope.eventType());
        if (type == null) {
            throw new AmqpRejectAndDontRequeueException(
                    "Unexpected event type " + envelope.eventType() + " on " + BookingEventsTopology.QUEUE);
        }
        if (envelope.eventVersion() != BookingEventPayload.VERSION) {
            throw new AmqpRejectAndDontRequeueException("Unsupported eventVersion "
                    + envelope.eventVersion() + " for " + envelope.eventType()
                    + "; this build reads version " + BookingEventPayload.VERSION);
        }
        if (!inbox.claim(BookingEventsTopology.CONSUMER, envelope.eventId(), clock.instant())) {
            log.info("Ignoring duplicate delivery of event {} to {}", envelope.eventId(), BookingEventsTopology.QUEUE);
            return;
        }
        BookingEventPayload payload = EventEnvelopeReader.payload(jsonMapper, envelope, BookingEventPayload.class);
        notifications.record(
                type,
                payload.userId(),
                notifications.contentFor(type, payload.reservationId(), payload.eventId()),
                envelope.eventId());
        log.info("Recorded {} for user {} from event {}", type, payload.userId(), envelope.eventId());
    }
}
