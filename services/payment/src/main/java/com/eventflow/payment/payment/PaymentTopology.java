package com.eventflow.payment.payment;

import java.util.List;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventflow.payment.shared.messaging.MessagingProperties;
import com.eventflow.payment.shared.messaging.RetryTopology;

/**
 * Broker topology of the payment consumer. Payment requests and refund compensations share one
 * queue because the same slice handles both and their effects are serialized per payment.
 */
@Configuration
public class PaymentTopology {

    /** Durable business queue owned by Payment. */
    public static final String QUEUE = "payment.payment-requested";
    /** Consumer name recorded in the inbox; it must not change, or old markers stop matching. */
    public static final String CONSUMER = "payment.payment-requested";

    @Bean
    public TopicExchange eventExchange(MessagingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    public TopicExchange deadLetterExchange(MessagingProperties properties) {
        return new TopicExchange(properties.deadLetterExchange(), true, false);
    }

    @Bean
    public Declarables paymentQueues(MessagingProperties properties) {
        return RetryTopology.forConsumer(
                properties.exchange(),
                properties.deadLetterExchange(),
                QUEUE,
                List.of(PaymentEvents.REQUESTED_ROUTING_KEY, PaymentEvents.REFUND_REQUESTED_ROUTING_KEY),
                properties.retry().delays());
    }
}
