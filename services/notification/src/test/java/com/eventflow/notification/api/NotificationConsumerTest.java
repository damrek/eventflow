package com.eventflow.notification.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.eventflow.notification.notification.BookingEventsTopology;
import com.eventflow.notification.notification.Notification;
import com.eventflow.notification.notification.NotificationRepository;
import com.eventflow.notification.notification.NotificationType;

/**
 * Phase 3 gate on the mailbox: one event produces one notification, a duplicate delivery produces
 * nothing new, the mailbox is paginated newest first and a message this service does not consume
 * goes to the DLQ instead of being retried forever.
 */
class NotificationConsumerTest extends NotificationIntegrationTest {

    private static final String EXCHANGE = "eventflow.events";
    private static final String DEAD_LETTER_QUEUE = BookingEventsTopology.QUEUE + ".dlq";
    private static final String USER = "user-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationRepository notifications;

    private final UUID reservationId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    /** Queues outlive a test and are shared by the class, so each test starts from empty ones. */
    @BeforeEach
    void drainQueues() {
        amqpAdmin.purgeQueue(BookingEventsTopology.QUEUE, false);
        amqpAdmin.purgeQueue(DEAD_LETTER_QUEUE, false);
    }

    @Test
    void recordsOneNotificationPerBookingEvent() throws Exception {
        send("BookingCancelled", "booking.cancelled", UUID.randomUUID());
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(countInbox()).isEqualTo(1));

        mockMvc.perform(get("/api/notifications").param("userId", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].type").value("BOOKING_CANCELLED"))
                .andExpect(jsonPath("$.content[0].status").value("UNREAD"))
                .andExpect(jsonPath("$.content[0].userId").value(USER))
                .andExpect(jsonPath("$.content[0].content").value(containsString(reservationId.toString())))
                .andExpect(jsonPath("$.totalElements").value(1));
        assertThat(countInbox()).isEqualTo(1);
    }

    @Test
    void ignoresADuplicateDeliveryOfTheSameEvent() {
        UUID envelopeId = UUID.randomUUID();
        send("BookingExpired", "booking.expired", envelopeId);
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(countInbox()).isEqualTo(1));

        send("BookingExpired", "booking.expired", envelopeId);
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(
                amqpAdmin.getQueueInfo(BookingEventsTopology.QUEUE).getMessageCount()).isZero());

        assertThat(notifications.count()).isEqualTo(1);
        assertThat(countInbox()).isEqualTo(1);
    }

    @Test
    void listsNewestFirstAndPaginates() throws Exception {
        notifications.save(Notification.of(NotificationType.BOOKING_CONFIRMED, USER, "first", UUID.randomUUID(),
                Instant.parse("2026-01-01T10:00:00Z")));
        notifications.save(Notification.of(NotificationType.BOOKING_CANCELLED, USER, "second", UUID.randomUUID(),
                Instant.parse("2026-01-02T10:00:00Z")));
        notifications.save(Notification.of(NotificationType.BOOKING_EXPIRED, "user-2", "other user", UUID.randomUUID(),
                Instant.parse("2026-01-03T10:00:00Z")));

        mockMvc.perform(get("/api/notifications").param("userId", USER).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].content").value("second"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/notifications").param("userId", USER).param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("first"));
    }

    @Test
    void requiresTheUserAndRejectsInvalidPagination() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_ID_REQUIRED"));

        mockMvc.perform(get("/api/notifications").param("userId", USER).param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
    }

    @Test
    void deadLettersAnEventThisServiceDoesNotConsume() {
        send("PaymentRequested", "booking.cancelled", UUID.randomUUID());

        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(
                amqpAdmin.getQueueInfo(DEAD_LETTER_QUEUE).getMessageCount()).isEqualTo(1));
        assertThat(notifications.count()).isZero();
    }

    private void send(String eventType, String routingKey, UUID envelopeId) {
        String envelope = """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-01-01T10:00:00Z",
                 "aggregateId":"%s","correlationId":"%s",
                 "payload":{"reservationId":"%s","eventId":"%s","userId":"%s"}}"""
                .formatted(envelopeId, eventType, reservationId, UUID.randomUUID(), reservationId, eventId, USER);
        rabbitTemplate.send(EXCHANGE, routingKey, MessageBuilder
                .withBody(envelope.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(envelopeId.toString())
                .build());
    }

    private int countInbox() {
        return jdbc.queryForObject(
                "select count(*) from processed_message where consumer_name = ?",
                Integer.class,
                BookingEventsTopology.CONSUMER);
    }
}
