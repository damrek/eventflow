package com.eventflow.booking.reservation;

import java.util.List;

import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventflow.booking.shared.messaging.MessagingProperties;
import com.eventflow.booking.shared.messaging.RetryTopology;

/**
 * Broker topology of the payment-result consumer. The shared exchanges are declared by
 * {@link com.eventflow.booking.inventory.EventPublishedTopology}; this queue only adds its own
 * durable queue, retry companions and DLQ.
 */
@Configuration
public class PaymentResultTopology {

    /** Durable business queue owned by Booking for payment results. */
    public static final String QUEUE = "booking.payment-result";
    /** Consumer name recorded in the inbox; it must not change, or old markers stop matching. */
    public static final String CONSUMER = "booking.payment-result";

    public static final String AUTHORIZED = "PaymentAuthorized";
    public static final String AUTHORIZED_ROUTING_KEY = "payment.authorized";
    public static final String REJECTED = "PaymentRejected";
    public static final String REJECTED_ROUTING_KEY = "payment.rejected";

    @Bean
    public Declarables paymentResultQueues(MessagingProperties properties) {
        return RetryTopology.forConsumer(
                properties.exchange(),
                properties.deadLetterExchange(),
                QUEUE,
                List.of(AUTHORIZED_ROUTING_KEY, REJECTED_ROUTING_KEY),
                properties.retry().delays());
    }
}
