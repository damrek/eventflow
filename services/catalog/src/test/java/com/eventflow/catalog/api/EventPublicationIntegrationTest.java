package com.eventflow.catalog.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.eventflow.catalog.shared.messaging.OutboxEvent;
import com.eventflow.catalog.shared.messaging.OutboxEventRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 3 gate for the producer: the outbox is written in the publishing transaction, the relay
 * sends the versioned envelope after a broker confirm, and a broker outage delays delivery instead
 * of losing the event.
 */
class EventPublicationIntegrationTest extends CatalogIntegrationTest {

    private static final String EXCHANGE = "eventflow.events";
    private static final String ROUTING_KEY = "event.published";
    private static final String PROBE_QUEUE = "test.event-published.probe";

    private static final String TICKETS = """
            [{"name":"General","price":25.00,"capacity":400},
             {"name":"VIP","price":60.00,"capacity":50}]""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private JsonMapper jsonMapper;

    /**
     * A test-owned queue on the real exchange: it proves what the broker received without
     * depending on another service's consumer.
     */
    @BeforeEach
    void bindProbeQueue() {
        // RabbitMQ 4 refuses non-exclusive transient queues, so the probe is durable and purged.
        Queue probe = QueueBuilder.durable(PROBE_QUEUE).build();
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(EXCHANGE, true, false)).with(ROUTING_KEY));
        amqpAdmin.purgeQueue(PROBE_QUEUE, false);
    }

    @Test
    void publishesEventPublishedEnvelopeOnce() throws Exception {
        String eventId = publishableEvent();

        publish(eventId);

        Message message = rabbitTemplate.receive(PROBE_QUEUE, 10_000);
        assertThat(message).isNotNull();
        JsonNode envelope = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(envelope.get("eventType").asString()).isEqualTo("EventPublished");
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(eventId);
        assertThat(envelope.get("eventId").asString()).isNotBlank();
        assertThat(envelope.get("occurredAt").asString()).isNotBlank();
        assertThat(envelope.get("correlationId").asString()).isNotBlank();
        assertThat(envelope.get("payload").get("eventId").asString()).isEqualTo(eventId);
        assertThat(envelope.get("payload").get("ticketTypes").size()).isEqualTo(2);
        assertThat(envelope.get("payload").get("ticketTypes").get(0).get("name").asString()).isEqualTo("General");
        assertThat(envelope.get("payload").get("ticketTypes").get(0).get("capacity").asInt()).isEqualTo(400);

        assertThat(outbox.findAll()).hasSize(1).allSatisfy(event -> assertThat(event.getPublishedAt()).isNotNull());

        // Publishing twice is still a conflict and must not queue a second event.
        publish(eventId, 409);
        assertThat(outbox.findAll()).hasSize(1);
        assertThat(rabbitTemplate.receive(PROBE_QUEUE, 1_000)).isNull();
    }

    /**
     * The Phase 3 gate for the producer. Testcontainers recreates the broker on restart, so the
     * test asserts what the design guarantees: the event survives the outage in the outbox and the
     * relay publishes it (and only then marks it) once the broker answers again. The wire format is
     * covered by the previous test.
     */
    @Test
    void publishesPendingOutboxEventsAfterTheBrokerReturns() throws Exception {
        String eventId = publishableEvent();
        RABBITMQ.stop();

        publish(eventId);

        await().pollDelay(1, SECONDS).atMost(10, SECONDS).untilAsserted(() -> assertThat(outbox.findAll())
                .singleElement()
                .satisfies(event -> assertThat(event.getPublishedAt()).as("unpublished during the outage").isNull()));

        RABBITMQ.start();

        await().atMost(60, SECONDS).ignoreExceptions().untilAsserted(() -> assertThat(outbox.findAll())
                .singleElement()
                .satisfies(event -> assertThat(event.getPublishedAt()).as("published after the broker returned").isNotNull()));
    }

    private String publishableEvent() throws Exception {
        String venue = mockMvc.perform(post("/api/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Arena Sur","address":"Main 1","capacity":5000}"""))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String venueId = jsonMapper.readTree(venue).get("id").asString();
        String event = mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Rock Night","venueId":"%s","startsAt":"2030-01-01T19:00:00Z",
                                 "ticketTypes":%s}""".formatted(venueId, TICKETS)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return jsonMapper.readTree(event).get("id").asString();
    }

    private void publish(String eventId) throws Exception {
        publish(eventId, 200);
    }

    private void publish(String eventId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/events/{eventId}/publish", eventId))
                .andExpect(status().is(expectedStatus));
    }
}
