package com.eventflow.booking.config;

import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.rabbitmq.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventflow.booking.inventory.EventPublishedTopology;
import com.eventflow.booking.reservation.PaymentResultTopology;
import com.eventflow.booking.shared.messaging.MessagingProperties;
import com.eventflow.booking.shared.messaging.RetryQueueMessageRecoverer;
import com.eventflow.booking.shared.messaging.RetryTopology;

/**
 * Booking now has two consumer queues, so the payment-result listener gets its own container
 * factory with its own recoverer: Boot applies a unique {@link MessageRecoverer} bean only to the
 * auto-configured factory, and a second bean would leave both without one.
 */
@Configuration
public class RabbitConsumerConfig {

    /** Applies {@code spring.rabbitmq.listener.simple.*} to the explicit factory as well. */
    @Bean
    public SimpleRabbitListenerContainerFactory paymentResultContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate,
            MessagingProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        // Boot applies the single MessageRecoverer bean, which belongs to the other queue; this
        // factory needs its own so a failed payment result reaches booking.payment-result.retry.N
        // and finally its DLQ.
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxRetries(0)
                .recoverer(new RetryQueueMessageRecoverer(
                        rabbitTemplate,
                        PaymentResultTopology.QUEUE,
                        RetryTopology.retryQueueNames(PaymentResultTopology.QUEUE, properties.retry().delays())))
                .build());
        return factory;
    }

    @Bean
    public MessageRecoverer eventPublishedRecoverer(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        return new RetryQueueMessageRecoverer(
                rabbitTemplate,
                EventPublishedTopology.QUEUE,
                RetryTopology.retryQueueNames(EventPublishedTopology.QUEUE, properties.retry().delays()));
    }
}
