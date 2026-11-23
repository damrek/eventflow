package com.eventflow.payment.config;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventflow.payment.payment.PaymentTopology;
import com.eventflow.payment.shared.messaging.MessagingProperties;
import com.eventflow.payment.shared.messaging.RetryQueueMessageRecoverer;
import com.eventflow.payment.shared.messaging.RetryTopology;

/**
 * Boot applies the unique {@link MessageRecoverer} bean to the listener container factory, so
 * every failing delivery is routed to the retry queues and finally to the DLQ without a custom
 * container factory.
 */
@Configuration
public class RabbitConsumerConfig {

    @Bean
    public MessageRecoverer paymentRequestedRecoverer(
            RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        return new RetryQueueMessageRecoverer(
                rabbitTemplate,
                PaymentTopology.QUEUE,
                RetryTopology.retryQueueNames(PaymentTopology.QUEUE, properties.retry().delays()));
    }
}
