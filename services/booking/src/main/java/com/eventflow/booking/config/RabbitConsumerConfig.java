package com.eventflow.booking.config;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventflow.booking.inventory.EventPublishedTopology;
import com.eventflow.booking.shared.messaging.MessagingProperties;
import com.eventflow.booking.shared.messaging.RetryQueueMessageRecoverer;
import com.eventflow.booking.shared.messaging.RetryTopology;

/**
 * Boot applies the unique {@link MessageRecoverer} bean to the listener container factory, so
 * every failing delivery is routed to the retry queues and finally to the DLQ without a custom
 * container factory. A second consumer queue needs its own recoverer and container factory.
 */
@Configuration
public class RabbitConsumerConfig {

    @Bean
    public MessageRecoverer eventPublishedRecoverer(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        return new RetryQueueMessageRecoverer(
                rabbitTemplate,
                EventPublishedTopology.QUEUE,
                RetryTopology.retryQueueNames(EventPublishedTopology.QUEUE, properties.retry().delays()));
    }
}
