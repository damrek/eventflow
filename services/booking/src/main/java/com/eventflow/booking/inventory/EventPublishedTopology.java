package com.eventflow.booking.inventory;

import java.util.List;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventflow.booking.shared.messaging.MessagingProperties;
import com.eventflow.booking.shared.messaging.RetryTopology;

/**
 * Broker topology of the {@code EventPublished} consumer: the shared topic exchange, the
 * dead-letter exchange and the durable queue with its retry and DLQ companions. Topology is
 * declared by the service that consumes it, so replacing the broker needs no manual setup.
 */
@Configuration
public class EventPublishedTopology {

    /** Durable business queue owned by Booking. */
    public static final String QUEUE = "booking.event-published";
    /** Consumer name recorded in the inbox; it must not change, or old markers stop matching. */
    public static final String CONSUMER = "booking.event-published";
    public static final String EVENT_TYPE = "EventPublished";
    public static final String ROUTING_KEY = "event.published";

    @Bean
    public TopicExchange eventExchange(MessagingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    public TopicExchange deadLetterExchange(MessagingProperties properties) {
        return new TopicExchange(properties.deadLetterExchange(), true, false);
    }

    @Bean
    public Declarables eventPublishedQueues(MessagingProperties properties) {
        return RetryTopology.forConsumer(
                properties.exchange(),
                properties.deadLetterExchange(),
                QUEUE,
                List.of(ROUTING_KEY),
                properties.retry().delays());
    }
}
