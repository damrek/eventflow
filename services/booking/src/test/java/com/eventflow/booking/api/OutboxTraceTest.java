package com.eventflow.booking.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import com.eventflow.booking.inventory.InventoryItemSpec;
import com.eventflow.booking.inventory.InventoryService;
import com.eventflow.booking.shared.messaging.OutboxRelay;

/**
 * S3 of Phase 7: the relay publishes seconds after the transaction that wrote the row, in another
 * thread. Without the value stored with the row the broker hop would start an unrelated trace, so
 * this test pins both halves — the row carries a W3C {@code traceparent}, and the message the
 * broker receives carries exactly that one — plus the scheduled-job path, where there is no span
 * to continue and the relay must still publish.
 */
@SpringBootTest(properties = {
        "booking.expiry.interval=PT1H",
        "eventflow.messaging.outbox.interval=PT1H",
        "eventflow.messaging.retry.delays=200ms,200ms,200ms",
        "spring.rabbitmq.connection-timeout=2s"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class OutboxTraceTest extends BookingIntegrationTest {

    private static final String EXCHANGE = "eventflow.events";
    private static final String ROUTING_KEY = "payment.requested";
    private static final String PROBE_QUEUE = "test.payment-requested.probe";
    private static final String TRACEPARENT = "traceparent";

    // Version 00, 32 hex trace id, 16 hex parent id, two hex flags (bit 0 sampled, bit 1 random).
    private static final String W3C_TRACEPARENT = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OutboxRelay relay;

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();

    @BeforeEach
    void bindProbeQueue() {
        // RabbitMQ 4 refuses non-exclusive transient queues, so the probe is durable and purged.
        Queue probe = QueueBuilder.durable(PROBE_QUEUE).build();
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(
                BindingBuilder.bind(probe).to(new TopicExchange(EXCHANGE, true, false)).with(ROUTING_KEY));
        amqpAdmin.purgeQueue(PROBE_QUEUE, false);
    }

    @Test
    void carriesTheWritersTraceThroughTheOutboxToTheBroker() throws Exception {
        inventory.provision(eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), 10)));
        String reservationId = createReservation();

        mockMvc.perform(post("/api/reservations/{id}/checkout", reservationId)
                        .header("X-User-Id", ApiFixtures.USER))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "select traceparent from outbox_event where event_type = 'PaymentRequested'");
        String stored = (String) row.get("traceparent");
        assertThat(stored)
                .as("the outbox row carries the traceparent of the span that wrote it")
                .matches(W3C_TRACEPARENT);

        // The scheduled relay is disabled in this context (PT1H), so the tick is explicit.
        assertThat(relay.relay()).isEqualTo(1);

        Message message = rabbitTemplate.receive(PROBE_QUEUE, SECONDS.toMillis(20));
        assertThat(message).isNotNull();
        String published = (String) message.getMessageProperties().getHeader(TRACEPARENT);
        assertThat(published)
                .as("the broker receives a W3C traceparent")
                .matches(W3C_TRACEPARENT);
        assertThat(field(published, 1))
                .as("the hop stays inside the trace stored with the row")
                .isEqualTo(field(stored, 1));
        assertThat(field(published, 2))
                .as("the relay hop is a new parent, not a verbatim copy of the stored header")
                .isNotEqualTo(field(stored, 2));
    }

    @Test
    void publishesARowThatHasNoTraceparent() {
        jdbc.update("insert into outbox_event (id, event_id, aggregate_id, event_type, routing_key, body,"
                        + " occurred_at, traceparent)"
                        + " values (?, ?, ?, 'Probe', ?, '{\"probe\":true}', now(), null)",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ROUTING_KEY);

        assertThat(relay.relay()).isEqualTo(1);

        Message message = rabbitTemplate.receive(PROBE_QUEUE, SECONDS.toMillis(20));
        assertThat(message)
                .as("a scheduled job has no parent span, and the relay must publish anyway")
                .isNotNull();
    }

    /** W3C layout: {@code 00-<trace id>-<parent id>-<flags>}; the hop advances the parent id only. */
    private static String field(String traceparent, int index) {
        return traceparent.split("-")[index];
    }

    private String createReservation() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", ApiFixtures.USER)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.reservationId");
    }
}
