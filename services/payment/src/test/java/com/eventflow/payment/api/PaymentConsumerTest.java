package com.eventflow.payment.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.eventflow.payment.payment.PaymentEvents;
import com.eventflow.payment.payment.PaymentTopology;
import com.eventflow.payment.shared.messaging.MessagingProperties;
import com.eventflow.payment.shared.messaging.RetryTopology;

/**
 * Phase 4 gate on Payment: an approval and a rejection publish their result, a duplicate delivery
 * produces one effect, a provider timeout spends the retry budget and ends in the DLQ without
 * finalizing anything, and a refund compensation is applied once.
 */
class PaymentConsumerTest extends PaymentIntegrationTest {

    private static final String EXCHANGE = "eventflow.events";
    private static final String DEAD_LETTER_QUEUE = PaymentTopology.QUEUE + ".dlq";
    private static final String USER = "user-1";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MessagingProperties messaging;

    @Autowired
    private MeterRegistry registry;

    private final UUID reservationId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID ticketTypeId = UUID.randomUUID();
    private final Set<UUID> envelopes = new LinkedHashSet<>();

    /** Queues outlive a test and are shared by the class, so each test starts from empty ones. */
    @BeforeEach
    void drainQueues() {
        amqpAdmin.purgeQueue(PaymentTopology.QUEUE, false);
        amqpAdmin.purgeQueue(DEAD_LETTER_QUEUE, false);
        List<String> retryQueues =
                RetryTopology.retryQueueNames(PaymentTopology.QUEUE, messaging.retry().delays());
        retryQueues.forEach(queue -> amqpAdmin.purgeQueue(queue, false));
    }

    @Test
    void authorizesThePaymentAndPublishesTheResult() {
        sendPaymentRequested(UUID.randomUUID(), new BigDecimal("110.00"));

        await().atMost(30, SECONDS).untilAsserted(() -> {
            assertThat(paymentStatus()).isEqualTo("AUTHORIZED");
            assertThat(countOutbox(PaymentEvents.AUTHORIZED)).isEqualTo(1);
        });

        assertThat(paymentCount()).isEqualTo(1);
        assertThat(attemptCount()).isEqualTo(1);
        assertThat(inboxCount()).isEqualTo(1);
        assertThat(PROVIDER.calls()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select provider_reference from payment where reservation_id = ?",
                        String.class,
                        reservationId))
                .isEqualTo("SIM-TEST");
    }

    @Test
    void rejectsThePaymentAndPublishesTheResult() {
        PROVIDER.script(FakePaymentProvider.Response.REJECTED);
        double rejected = failures("REJECTED");

        sendPaymentRequested(UUID.randomUUID(), new BigDecimal("5.00"));

        await().atMost(30, SECONDS).untilAsserted(() -> {
            assertThat(paymentStatus()).isEqualTo("REJECTED");
            assertThat(countOutbox(PaymentEvents.REJECTED)).isEqualTo(1);
        });

        assertThat(attemptCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select error from payment_attempt", String.class))
                .isEqualTo("Simulated decline");
        assertThat(jdbc.queryForObject(
                        "select rejection_reason from payment where reservation_id = ?",
                        String.class,
                        reservationId))
                .isEqualTo("Simulated decline");
        assertThat(failures("REJECTED")).isEqualTo(rejected + 1);
    }

    @Test
    void ignoresADuplicateDeliveryOfTheSameEvent() {
        UUID envelopeId = UUID.randomUUID();
        sendPaymentRequested(envelopeId, new BigDecimal("110.00"));
        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(paymentStatus()).isEqualTo("AUTHORIZED"));

        sendPaymentRequested(envelopeId, new BigDecimal("110.00"));
        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(
                amqpAdmin.getQueueInfo(PaymentTopology.QUEUE).getMessageCount()).isZero());

        assertThat(paymentCount()).isEqualTo(1);
        assertThat(attemptCount()).isEqualTo(1);
        assertThat(countOutbox(PaymentEvents.AUTHORIZED)).isEqualTo(1);
        assertThat(PROVIDER.calls()).isEqualTo(1);
    }

    @Test
    void providerTimeoutSpendsTheRetriesAndEndsInTheDeadLetterQueue() {
        PROVIDER.always(FakePaymentProvider.Response.SLOW);
        double timeouts = failures("TIMEOUT");

        sendPaymentRequested(UUID.randomUUID(), new BigDecimal("110.00"));

        await().atMost(60, SECONDS).untilAsserted(() -> assertThat(
                amqpAdmin.getQueueInfo(DEAD_LETTER_QUEUE).getMessageCount()).isEqualTo(1));

        assertThat(paymentCount()).isZero();
        assertThat(countOutbox(PaymentEvents.AUTHORIZED)).isZero();
        assertThat(countOutbox(PaymentEvents.REJECTED)).isZero();
        assertThat(PROVIDER.calls()).isGreaterThanOrEqualTo(1);
        assertThat(failures("TIMEOUT"))
                .as("a provider that never answers is counted as a timeout on every attempt")
                .isGreaterThan(timeouts);
    }

    @Test
    void refundsAnAuthorizedPaymentOnce() {
        sendPaymentRequested(UUID.randomUUID(), new BigDecimal("110.00"));
        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(paymentStatus()).isEqualTo("AUTHORIZED"));
        UUID paymentId = paymentId();

        sendRefundRequested(UUID.randomUUID(), paymentId);
        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(paymentStatus()).isEqualTo("REFUNDED"));

        sendRefundRequested(UUID.randomUUID(), paymentId);
        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(
                amqpAdmin.getQueueInfo(PaymentTopology.QUEUE).getMessageCount()).isZero());

        assertThat(paymentStatus()).isEqualTo("REFUNDED");
        assertThat(attemptCount()).isEqualTo(1);
    }

    @Test
    void deadLettersAnEventItDoesNotConsume() {
        send("PaymentSettled", PaymentEvents.REQUESTED_ROUTING_KEY, UUID.randomUUID(), "{}");

        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(
                amqpAdmin.getQueueInfo(DEAD_LETTER_QUEUE).getMessageCount()).isEqualTo(1));

        assertThat(paymentCount()).isZero();
    }

    private double failures(String reason) {
        Counter counter = registry.find("payment.failures").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private void sendPaymentRequested(UUID envelopeId, BigDecimal amount) {
        send(
                PaymentEvents.REQUESTED,
                PaymentEvents.REQUESTED_ROUTING_KEY,
                envelopeId,
                """
                {"reservationId":"%s","eventId":"%s","userId":"%s","amount":%s,"currency":"EUR",
                 "items":[{"ticketTypeId":"%s","quantity":1}]}"""
                        .formatted(reservationId, eventId, USER, amount, ticketTypeId));
    }

    private void sendRefundRequested(UUID envelopeId, UUID paymentId) {
        send(
                PaymentEvents.REFUND_REQUESTED,
                PaymentEvents.REFUND_REQUESTED_ROUTING_KEY,
                envelopeId,
                """
                {"paymentId":"%s","reservationId":"%s","amount":110.00,"currency":"EUR","reason":"late"}"""
                        .formatted(paymentId, reservationId));
    }

    private void send(String eventType, String routingKey, UUID envelopeId, String payloadJson) {
        envelopes.add(envelopeId);
        String envelope = """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-01-01T10:00:00Z",
                 "aggregateId":"%s","correlationId":"%s","payload":%s}"""
                .formatted(envelopeId, eventType, reservationId, UUID.randomUUID(), payloadJson);
        rabbitTemplate.send(EXCHANGE, routingKey, MessageBuilder
                .withBody(envelope.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(envelopeId.toString())
                .build());
    }

    private String paymentStatus() {
        List<String> statuses = jdbc.queryForList(
                "select status from payment where reservation_id = ?", String.class, reservationId);
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private UUID paymentId() {
        return jdbc.queryForObject(
                "select id from payment where reservation_id = ?", UUID.class, reservationId);
    }

    private int paymentCount() {
        return jdbc.queryForObject(
                "select count(*) from payment where reservation_id = ?", Integer.class, reservationId);
    }

    private int attemptCount() {
        return jdbc.queryForObject(
                """
                select count(*) from payment_attempt attempt
                  join payment on payment.id = attempt.payment_id
                 where payment.reservation_id = ?
                """,
                Integer.class,
                reservationId);
    }

    /** Counts only the deliveries this test published, so a neighbour test cannot skew it. */
    private int inboxCount() {
        if (envelopes.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(envelopes.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(PaymentTopology.CONSUMER);
        arguments.addAll(envelopes);
        return jdbc.queryForObject(
                "select count(*) from processed_message where consumer_name = ? and event_id in ("
                        + placeholders + ")",
                Integer.class,
                arguments.toArray());
    }

    private int countOutbox(String eventType) {
        return jdbc.queryForObject(
                """
                select count(*) from outbox_event outbox
                  join payment on payment.id = outbox.aggregate_id
                 where payment.reservation_id = ?
                   and outbox.event_type = ?
                """,
                Integer.class,
                reservationId,
                eventType);
    }
}
