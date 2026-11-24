package com.eventflow.booking.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.eventflow.booking.shared.messaging.EventLogContext;
import com.eventflow.booking.shared.messaging.Inbox;

import tools.jackson.databind.json.JsonMapper;

/**
 * S4 of Phase 7: the unit test pins the utility, this one pins that the listener is actually wired
 * to it and that the ids do not survive a thrown handler.
 */
class PaymentResultListenerMdcTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final UUID PAYMENT_ID = UUID.randomUUID();

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ReservationPaymentService payments = mock(ReservationPaymentService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final PaymentResultListener listener = new PaymentResultListener(
            inbox, payments, jsonMapper, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void exposesTheIdsOnlyWhileTheHandlerRuns() {
        when(inbox.claim(any(), any(), any())).thenReturn(true);
        AtomicReference<String> event = new AtomicReference<>();
        AtomicReference<String> reservation = new AtomicReference<>();
        doAnswer(invocation -> {
            event.set(MDC.get(EventLogContext.EVENT_ID));
            reservation.set(MDC.get(EventLogContext.RESERVATION_ID));
            return null;
        }).when(payments).authorized(any(), any());

        listener.onPaymentResult(message());

        assertThat(event.get()).isEqualTo(EVENT_ID.toString());
        assertThat(reservation.get()).isEqualTo(RESERVATION_ID.toString());
        assertThat(MDC.get(EventLogContext.EVENT_ID)).isNull();
        assertThat(MDC.get(EventLogContext.RESERVATION_ID)).isNull();
    }

    @Test
    void clearsTheIdsWhenTheHandlerThrows() {
        when(inbox.claim(any(), any(), any())).thenReturn(true);
        doThrow(new PermanentReservationException("reservation is gone")).when(payments).authorized(any(), any());

        assertThatThrownBy(() -> listener.onPaymentResult(message()))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThat(MDC.get(EventLogContext.EVENT_ID)).isNull();
        assertThat(MDC.get(EventLogContext.RESERVATION_ID)).isNull();
    }

    private Message message() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", PAYMENT_ID.toString());
        payload.put("reservationId", RESERVATION_ID.toString());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", EVENT_ID.toString());
        envelope.put("eventType", PaymentResultTopology.AUTHORIZED);
        envelope.put("eventVersion", PaymentResultPayload.VERSION);
        envelope.put("occurredAt", "2026-01-01T00:00:00Z");
        envelope.put("aggregateId", PAYMENT_ID.toString());
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.put("payload", payload);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(jsonMapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8), properties);
    }
}
