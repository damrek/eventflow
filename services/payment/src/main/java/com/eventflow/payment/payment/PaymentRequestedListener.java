package com.eventflow.payment.payment;

import java.time.Clock;

import com.eventflow.payment.shared.messaging.EventEnvelope;
import com.eventflow.payment.shared.messaging.EventEnvelopeReader;
import com.eventflow.payment.shared.messaging.Inbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point of the payment slice: {@code PaymentRequested} asks the provider and publishes the
 * result, {@code PaymentRefundRequested} compensates a late authorization. The inbox claim and the
 * effect share one transaction; the container acks only after the commit.
 *
 * <p>A permanent failure (unknown type, unsupported version, malformed payload, illegal
 * transition) is rejected without requeue and ends in the DLQ; a provider failure is left to the
 * retry queues and never reaches this catch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentRequestedListener {

    private final Inbox inbox;
    private final PaymentService payments;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @RabbitListener(queues = PaymentTopology.QUEUE)
    @Transactional
    public void onPaymentEvent(Message message) {
        EventEnvelope envelope = EventEnvelopeReader.read(jsonMapper, message);
        if (!inbox.claim(PaymentTopology.CONSUMER, envelope.eventId(), clock.instant())) {
            log.info("Ignoring duplicate delivery of event {} to {}", envelope.eventId(), PaymentTopology.QUEUE);
            return;
        }
        try {
            switch (envelope.eventType()) {
                case PaymentEvents.REQUESTED -> {
                    requireVersion(envelope, PaymentRequestedPayload.VERSION);
                    payments.requestPayment(EventEnvelopeReader.payload(
                            jsonMapper, envelope, PaymentRequestedPayload.class));
                }
                case PaymentEvents.REFUND_REQUESTED -> {
                    requireVersion(envelope, RefundRequestedPayload.VERSION);
                    payments.refund(EventEnvelopeReader.payload(
                            jsonMapper, envelope, RefundRequestedPayload.class));
                }
                default -> throw new AmqpRejectAndDontRequeueException(
                        "Unexpected event type " + envelope.eventType() + " on " + PaymentTopology.QUEUE);
            }
        } catch (PermanentPaymentException permanent) {
            throw new AmqpRejectAndDontRequeueException(permanent.getMessage(), permanent);
        }
    }

    private static void requireVersion(EventEnvelope envelope, int supportedVersion) {
        if (envelope.eventVersion() != supportedVersion) {
            throw new AmqpRejectAndDontRequeueException("Unsupported eventVersion "
                    + envelope.eventVersion() + " for " + envelope.eventType()
                    + "; this build reads version " + supportedVersion);
        }
    }
}
