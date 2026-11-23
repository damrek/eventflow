package com.eventflow.payment.shared.messaging;

import java.nio.charset.StandardCharsets;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;

import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the versioned envelope from a raw broker message. Anything unreadable is a permanent
 * failure: rejecting it without requeue sends it to the DLQ instead of retrying a payload that
 * will never parse.
 */
public final class EventEnvelopeReader {

    private EventEnvelopeReader() {
    }

    public static EventEnvelope read(JsonMapper jsonMapper, Message message) {
        try {
            return jsonMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), EventEnvelope.class);
        } catch (RuntimeException malformed) {
            throw new AmqpRejectAndDontRequeueException(
                    "Message " + message.getMessageProperties().getMessageId() + " is not a valid event envelope",
                    malformed);
        }
    }

    public static <T> T payload(JsonMapper jsonMapper, EventEnvelope envelope, Class<T> type) {
        try {
            return jsonMapper.treeToValue(envelope.payload(), type);
        } catch (RuntimeException malformed) {
            throw new AmqpRejectAndDontRequeueException(
                    "Payload of event " + envelope.eventId() + " does not match " + type.getSimpleName(), malformed);
        }
    }
}
