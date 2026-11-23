package com.eventflow.notification.notification;

import java.util.List;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventflow.notification.shared.messaging.MessagingProperties;
import com.eventflow.notification.shared.messaging.RetryTopology;

/**
 * Broker topology of the booking-events consumer. One queue serves the three booking events
 * because they share the payload and the effect (one mailbox entry each).
 */
@Configuration
public class BookingEventsTopology {

    /** Durable business queue owned by Notification. */
    public static final String QUEUE = "notification.booking-events";
    /** Consumer name recorded in the inbox; it must not change, or old markers stop matching. */
    public static final String CONSUMER = "notification.booking-events";

    public static final String CONFIRMED_ROUTING_KEY = "booking.confirmed";
    public static final String CANCELLED_ROUTING_KEY = "booking.cancelled";
    public static final String EXPIRED_ROUTING_KEY = "booking.expired";
    public static final String PAYMENT_FAILED_ROUTING_KEY = "booking.payment-failed";

    @Bean
    public TopicExchange eventExchange(MessagingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    public TopicExchange deadLetterExchange(MessagingProperties properties) {
        return new TopicExchange(properties.deadLetterExchange(), true, false);
    }

    @Bean
    public Declarables bookingEventQueues(MessagingProperties properties) {
        return RetryTopology.forConsumer(
                properties.exchange(),
                properties.deadLetterExchange(),
                QUEUE,
                List.of(CONFIRMED_ROUTING_KEY, CANCELLED_ROUTING_KEY, EXPIRED_ROUTING_KEY, PAYMENT_FAILED_ROUTING_KEY),
                properties.retry().delays());
    }
}
