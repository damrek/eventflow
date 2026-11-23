package com.eventflow.notification.config;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventflow.notification.notification.BookingEventsTopology;
import com.eventflow.notification.shared.messaging.MessagingProperties;
import com.eventflow.notification.shared.messaging.RetryQueueMessageRecoverer;
import com.eventflow.notification.shared.messaging.RetryTopology;

/**
 * Boot applies the unique {@link MessageRecoverer} bean to the listener container factory, so
 * every failing delivery is routed to the retry queues and finally to the DLQ without a custom
 * container factory.
 */
@Configuration
public class RabbitConsumerConfig {

    @Bean
    public MessageRecoverer bookingEventsRecoverer(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        return new RetryQueueMessageRecoverer(
                rabbitTemplate,
                BookingEventsTopology.QUEUE,
                RetryTopology.retryQueueNames(BookingEventsTopology.QUEUE, properties.retry().delays()));
    }
}
