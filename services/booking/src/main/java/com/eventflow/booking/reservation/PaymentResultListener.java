package com.eventflow.booking.reservation;

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
 * Consumes the provider results Payment publishes. The inbox claim and the reservation effect run
 * in one transaction, so a redelivery confirms or releases nothing twice; an unknown or
 * unreadable result is rejected without requeue and ends in the DLQ.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentResultListener {

    private final Inbox inbox;
    private final ReservationPaymentService payments;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @RabbitListener(queues = PaymentResultTopology.QUEUE, containerFactory = "paymentResultContainerFactory")
    @Transactional
    public void onPaymentResult(Message message) {
        EventEnvelope envelope = EventEnvelopeReader.read(jsonMapper, message);
        requireContract(envelope);
        if (!inbox.claim(PaymentResultTopology.CONSUMER, envelope.eventId(), clock.instant())) {
            log.info("Ignoring duplicate delivery of event {} to {}", envelope.eventId(), PaymentResultTopology.QUEUE);
            return;
        }
        PaymentResultPayload payload = EventEnvelopeReader.payload(jsonMapper, envelope, PaymentResultPayload.class);
        try {
            if (PaymentResultTopology.AUTHORIZED.equals(envelope.eventType())) {
                payments.authorized(payload.reservationId(), payload.paymentId());
            } else {
                payments.rejected(payload.reservationId(), payload.paymentId(), payload.reason());
            }
        } catch (PermanentReservationException permanent) {
            throw new AmqpRejectAndDontRequeueException(permanent.getMessage(), permanent);
        }
    }

    private static void requireContract(EventEnvelope envelope) {
        if (!PaymentResultTopology.AUTHORIZED.equals(envelope.eventType())
                && !PaymentResultTopology.REJECTED.equals(envelope.eventType())) {
            throw new AmqpRejectAndDontRequeueException(
                    "Unexpected event type " + envelope.eventType() + " on " + PaymentResultTopology.QUEUE);
        }
        if (envelope.eventVersion() != PaymentResultPayload.VERSION) {
            throw new AmqpRejectAndDontRequeueException("Unsupported eventVersion "
                    + envelope.eventVersion() + " for " + envelope.eventType()
                    + "; this build reads version " + PaymentResultPayload.VERSION);
        }
    }
}
