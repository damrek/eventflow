package com.eventflow.booking.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import com.eventflow.booking.inventory.EventPublishedTopology;

/**
 * Phase 3 gate on the consumer side: inventory is provisioned only by a broker event, delivered
 * once per event id, and a message this build cannot or must not apply ends in the DLQ instead of
 * looping forever.
 */
@SpringBootTest(properties = {
        "booking.expiry.interval=PT1H",
        "eventflow.messaging.outbox.interval=200ms",
        "eventflow.messaging.retry.delays=200ms,200ms,200ms",
        "spring.rabbitmq.connection-timeout=2s"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class EventPublishedConsumerTest extends BookingIntegrationTest {

    private static final String EXCHANGE = "eventflow.events";
    private static final String DEAD_LETTER_QUEUE = EventPublishedTopology.QUEUE + ".dlq";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();

    /** Queues outlive a test and are shared by the class, so each test starts from empty ones. */
    @BeforeEach
    void drainQueues() {
        amqpAdmin.purgeQueue(EventPublishedTopology.QUEUE, false);
        amqpAdmin.purgeQueue(DEAD_LETTER_QUEUE, false);
    }

    @Test
    void provisionsInventoryFromASingleEventPublished() throws Exception {
        send(eventId, UUID.randomUUID(), 1, offer(400, 50));

        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(capacityOf(general)).isEqualTo(400));
        assertThat(capacityOf(vip)).isEqualTo(50);
        assertThat(countInbox()).isEqualTo(1);
        mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketTypes", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    void ignoresADuplicateDeliveryOfTheSameEvent() throws Exception {
        UUID envelopeId = UUID.randomUUID();
        send(eventId, envelopeId, 1, offer(400, 50));
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(countInbox()).isEqualTo(1));
        awaitDrained();

        send(eventId, envelopeId, 1, offer(400, 50));
        awaitDrained();

        assertThat(countInbox()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from event_inventory where event_id = ?", Integer.class, eventId)).isEqualTo(2);
    }

    @Test
    void deadLettersAnEventVersionThisBuildCannotRead() throws Exception {
        send(eventId, UUID.randomUUID(), 99, offer(400, 50));

        await().atMost(20, SECONDS).untilAsserted(() ->
                assertThat(deadLettered()).isEqualTo(1L));
        assertThat(countInbox()).isZero();
        mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
    }

    @Test
    void deadLettersAnOfferThatConflictsWithTheProvisionedInventory() throws Exception {
        send(eventId, UUID.randomUUID(), 1, offer(400, 50));
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(countInbox()).isEqualTo(1));

        send(eventId, UUID.randomUUID(), 1, offer(500, 50));

        // Three configured retries (200 ms each) and then the DLQ.
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(deadLettered()).isEqualTo(1L));
        assertThat(jdbc.queryForObject(
                "select capacity from event_inventory where event_id = ? and ticket_type_id = ?",
                Integer.class, eventId, general)).isEqualTo(400);
    }

    private void send(UUID payloadEventId, UUID envelopeId, int version, String ticketTypes) {
        String envelope = """
                {"eventId":"%s","eventType":"EventPublished","eventVersion":%d,
                 "occurredAt":"2026-01-01T10:00:00Z","aggregateId":"%s","correlationId":"%s",
                 "payload":{"eventId":"%s","name":"Rock Night","startsAt":"2030-01-01T19:00:00Z",
                            "ticketTypes":%s}}"""
                .formatted(envelopeId, version, payloadEventId, UUID.randomUUID(), payloadEventId, ticketTypes);
        rabbitTemplate.send(EXCHANGE, "event.published", MessageBuilder
                .withBody(envelope.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(envelopeId.toString())
                .build());
    }

    private String offer(int generalCapacity, int vipCapacity) {
        return """
                [{"ticketTypeId":"%s","name":"General","price":25.00,"capacity":%d},
                 {"ticketTypeId":"%s","name":"VIP","price":60.00,"capacity":%d}]"""
                .formatted(general, generalCapacity, vip, vipCapacity);
    }

    private Integer capacityOf(UUID ticketTypeId) {
        return jdbc.queryForObject(
                "select capacity from event_inventory where event_id = ? and ticket_type_id = ?",
                Integer.class,
                eventId,
                ticketTypeId);
    }

    private int countInbox() {
        return jdbc.queryForObject(
                "select count(*) from processed_message where consumer_name = ?",
                Integer.class,
                EventPublishedTopology.CONSUMER);
    }

    private long deadLettered() {
        return amqpAdmin.getQueueInfo(DEAD_LETTER_QUEUE).getMessageCount();
    }

    private void awaitDrained() {
        await().atMost(20, SECONDS).untilAsserted(() ->
                assertThat(amqpAdmin.getQueueInfo(EventPublishedTopology.QUEUE).getMessageCount()).isZero());
    }
}
