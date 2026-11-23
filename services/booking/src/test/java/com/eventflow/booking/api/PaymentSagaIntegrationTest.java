package com.eventflow.booking.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.eventflow.booking.inventory.InventoryItemSpec;
import com.eventflow.booking.inventory.InventoryService;
import com.eventflow.booking.reservation.PaymentResultTopology;
import com.eventflow.booking.reservation.ReservationExpiryWorker;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 4 gate on Booking: checkout requests the payment, an authorization confirms and sells the
 * units, a rejection fails the reservation and releases them, a duplicate result changes nothing,
 * and an authorization that arrives after the deadline is compensated with a refund instead of
 * selling inventory that was already released.
 */
@SpringBootTest(properties = {
        "booking.expiry.interval=PT1H",
        "eventflow.messaging.outbox.interval=200ms",
        "eventflow.messaging.retry.delays=200ms,200ms,200ms",
        "spring.rabbitmq.connection-timeout=2s"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PaymentSagaIntegrationTest extends BookingIntegrationTest {

    private static final String EXCHANGE = "eventflow.events";
    private static final String USER = ApiFixtures.USER;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private ReservationExpiryWorker expiryWorker;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();

    @BeforeEach
    void drainQueues() {
        amqpAdmin.purgeQueue(PaymentResultTopology.QUEUE, false);
        amqpAdmin.purgeQueue(PaymentResultTopology.QUEUE + ".dlq", false);
    }

    @Test
    void checkoutRequestsThePaymentAndMovesTheReservationToPending() throws Exception {
        provision(10);
        String reservationId = createReservation(2);

        mockMvc.perform(post("/api/reservations/{id}/checkout", reservationId).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        assertThat(reservationStatus(reservationId)).isEqualTo("PENDING_PAYMENT");
        assertThat(outboxCount("PaymentRequested", UUID.fromString(reservationId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select body from outbox_event where event_type = 'PaymentRequested'", String.class))
                .contains("\"amount\":50.00")
                .contains("\"currency\":\"EUR\"")
                .contains("\"reservationId\":\"" + reservationId + "\"");
    }

    @Test
    void refusesASecondCheckoutOrACheckoutPastTheDeadline() throws Exception {
        provision(10);
        String reservationId = createReservation(1);

        mockMvc.perform(post("/api/reservations/{id}/checkout", reservationId).header("X-User-Id", USER))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/checkout", reservationId).header("X-User-Id", USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_PAYABLE"));

        String expired = createReservation(1);
        makeDue(expired);
        mockMvc.perform(post("/api/reservations/{id}/checkout", expired).header("X-User-Id", USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));

        assertThat(outboxCount("PaymentRequested", UUID.fromString(reservationId))).isEqualTo(1);
        assertThat(outboxCount("PaymentRequested", UUID.fromString(expired))).isZero();
    }

    @Test
    void refusesToCancelWhileThePaymentIsPending() throws Exception {
        provision(10);
        String reservationId = createReservation(1);
        mockMvc.perform(post("/api/reservations/{id}/checkout", reservationId).header("X-User-Id", USER))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/reservations/{id}/cancel", reservationId).header("X-User-Id", USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"));
    }

    @Test
    void confirmsTheReservationAndSellsTheUnitsWhenThePaymentIsAuthorized() throws Exception {
        provision(10);
        String reservationId = createReservation(2);
        checkout(reservationId);

        sendAuthorized(UUID.randomUUID(), UUID.fromString(reservationId));

        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED"));
        assertThat(available()).isEqualTo(8);
        assertThat(held()).isZero();
        assertThat(sold()).isEqualTo(2);
        assertThat(outboxCount("BookingConfirmed", UUID.fromString(reservationId))).isEqualTo(1);
    }

    @Test
    void failsTheReservationAndReleasesTheUnitsWhenThePaymentIsRejected() throws Exception {
        provision(10);
        String reservationId = createReservation(3);
        checkout(reservationId);

        sendRejected(UUID.randomUUID(), UUID.fromString(reservationId));

        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(reservationStatus(reservationId)).isEqualTo("PAYMENT_FAILED"));
        assertThat(available()).isEqualTo(10);
        assertThat(held()).isZero();
        assertThat(sold()).isZero();
        assertThat(outboxCount("BookingPaymentFailed", UUID.fromString(reservationId))).isEqualTo(1);
    }

    @Test
    void ignoresADuplicateAuthorization() throws Exception {
        provision(10);
        String reservationId = createReservation(2);
        checkout(reservationId);
        UUID paymentId = UUID.randomUUID();
        UUID envelopeId = UUID.randomUUID();

        sendAuthorized(envelopeId, UUID.fromString(reservationId), paymentId);
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED"));

        sendAuthorized(envelopeId, UUID.fromString(reservationId), paymentId);
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(
                amqpAdmin.getQueueInfo(PaymentResultTopology.QUEUE).getMessageCount()).isZero());

        assertThat(sold()).isEqualTo(2);
        assertThat(outboxCount("BookingConfirmed", UUID.fromString(reservationId))).isEqualTo(1);
        assertThat(inboxCount()).isEqualTo(1);
    }

    @Test
    void compensatesAnAuthorizationThatArrivesAfterTheHoldExpired() throws Exception {
        provision(10);
        String reservationId = createReservation(2);
        checkout(reservationId);
        makeDue(reservationId);
        assertThat(expiryWorker.expireDue()).isEqualTo(1);

        UUID paymentId = UUID.randomUUID();
        sendAuthorized(UUID.randomUUID(), UUID.fromString(reservationId), paymentId);

        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(
                outboxCount("PaymentRefundRequested", UUID.fromString(reservationId))).isEqualTo(1));

        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertThat(available()).isEqualTo(10);
        assertThat(held()).isZero();
        assertThat(sold()).isZero();
        assertThat(jdbc.queryForObject(
                        "select payment_id from reservation where id = ?", UUID.class, UUID.fromString(reservationId)))
                .isEqualTo(paymentId);
        assertThat(outboxCount("BookingConfirmed", UUID.fromString(reservationId))).isZero();
    }

    @Test
    void compensatesAnAuthorizationWhenTheDeadlinePassedBeforeTheWorkerRan() throws Exception {
        provision(10);
        String reservationId = createReservation(2);
        checkout(reservationId);
        makeDue(reservationId);

        sendAuthorized(UUID.randomUUID(), UUID.fromString(reservationId));

        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED"));
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(
                outboxCount("PaymentRefundRequested", UUID.fromString(reservationId))).isEqualTo(1));

        assertThat(available()).isEqualTo(10);
        assertThat(held()).isZero();
        assertThat(sold()).isZero();
        assertThat(outboxCount("BookingConfirmed", UUID.fromString(reservationId))).isZero();
    }

    @Test
    void expiresAPendingPaymentReservationAndReleasesItsUnits() throws Exception {
        provision(10);
        String reservationId = createReservation(2);
        checkout(reservationId);
        makeDue(reservationId);

        assertThat(expiryWorker.expireDue()).isEqualTo(1);

        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertThat(available()).isEqualTo(10);
        assertThat(held()).isZero();
        assertThat(outboxCount("BookingExpired", UUID.fromString(reservationId))).isEqualTo(1);
    }

    private void provision(int capacity) {
        inventory.provision(eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), capacity)));
    }

    private String createReservation(int quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", USER)
                        .header("Idempotency-Key", "order-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservation(eventId.toString(), general.toString(), quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.reservationId");
    }

    private void checkout(String reservationId) throws Exception {
        mockMvc.perform(post("/api/reservations/{id}/checkout", reservationId).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    private void sendAuthorized(UUID envelopeId, UUID reservationId) {
        sendAuthorized(envelopeId, reservationId, UUID.randomUUID());
    }

    private void sendAuthorized(UUID envelopeId, UUID reservationId, UUID paymentId) {
        send("PaymentAuthorized", PaymentResultTopology.AUTHORIZED_ROUTING_KEY, envelopeId, reservationId, paymentId,
                "\"providerReference\":\"SIM-TEST\"");
    }

    private void sendRejected(UUID envelopeId, UUID reservationId) {
        send("PaymentRejected", PaymentResultTopology.REJECTED_ROUTING_KEY, envelopeId, reservationId,
                UUID.randomUUID(), "\"reason\":\"Simulated decline\"");
    }

    private void send(
            String eventType,
            String routingKey,
            UUID envelopeId,
            UUID reservationId,
            UUID paymentId,
            String extraField) {
        String payload = """
                {"paymentId":"%s","reservationId":"%s","eventId":"%s","userId":"%s",
                 "amount":50.00,"currency":"EUR",%s}"""
                .formatted(paymentId, reservationId, eventId, USER, extraField);
        String envelope = """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-01-01T10:00:00Z",
                 "aggregateId":"%s","correlationId":"%s","payload":%s}"""
                .formatted(envelopeId, eventType, paymentId, UUID.randomUUID(), payload);
        rabbitTemplate.send(EXCHANGE, routingKey, MessageBuilder
                .withBody(envelope.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(envelopeId.toString())
                .build());
    }

    private void makeDue(String reservationId) {
        int updated = jdbc.update(
                "update reservation set expires_at = now() - interval '1 minute' where id = ?",
                UUID.fromString(reservationId));
        assertThat(updated).isEqualTo(1);
    }

    private String reservationStatus(String reservationId) {
        return jdbc.queryForObject(
                "select status from reservation where id = ?", String.class, UUID.fromString(reservationId));
    }

    private int outboxCount(String eventType, UUID aggregateId) {
        return jdbc.queryForObject(
                "select count(*) from outbox_event where event_type = ? and aggregate_id = ?",
                Integer.class,
                eventType,
                aggregateId);
    }

    private int inboxCount() {
        return jdbc.queryForObject(
                "select count(*) from processed_message where consumer_name = ?",
                Integer.class,
                PaymentResultTopology.CONSUMER);
    }

    private int available() throws Exception {
        return availability("available");
    }

    private int held() throws Exception {
        return availability("held");
    }

    private int sold() throws Exception {
        return availability("sold");
    }

    private int availability(String field) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andExpect(status().isOk())
                .andReturn();
        List<Integer> values = JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.ticketTypes[?(@.ticketTypeId=='" + general + "')]." + field);
        return values.getFirst();
    }
}
