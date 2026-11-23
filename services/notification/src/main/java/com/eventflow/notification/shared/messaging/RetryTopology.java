package com.eventflow.notification.shared.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Builds the queue set of one consumer: a durable business queue, one retry queue per configured
 * delay and a DLQ. A failing delivery is republished to {@code <queue>.retry.N}, whose message TTL
 * expires it back into the exchange and therefore into the business queue; after the last delay
 * the delivery is rejected without requeue and the business queue's dead-letter arguments move it
 * to {@code <queue>.dlq}.
 *
 * <p>The business queue is also bound to its own name, which is the routing key the retry queues
 * use to return a message without mixing it with the events the queue consumes.
 */
public final class RetryTopology {

    private RetryTopology() {
    }

    public static Declarables forConsumer(
            String exchange,
            String deadLetterExchange,
            String queueName,
            List<String> routingKeys,
            List<Duration> retryDelays) {
        TopicExchange events = new TopicExchange(exchange, true, false);
        TopicExchange deadLetters = new TopicExchange(deadLetterExchange, true, false);
        String deadLetterQueueName = queueName + ".dlq";

        Queue queue = QueueBuilder.durable(queueName)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterQueueName)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(deadLetterQueueName).build();

        List<Declarable> declarables = new ArrayList<>();
        declarables.add(queue);
        declarables.add(deadLetterQueue);
        declarables.add(BindingBuilder.bind(queue).to(events).with(queueName));
        for (String routingKey : routingKeys) {
            declarables.add(BindingBuilder.bind(queue).to(events).with(routingKey));
        }
        declarables.add(BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(deadLetterQueueName));

        List<String> retryQueues = retryQueueNames(queueName, retryDelays);
        for (int attempt = 0; attempt < retryQueues.size(); attempt++) {
            declarables.add(QueueBuilder.durable(retryQueues.get(attempt))
                    .ttl(Math.toIntExact(retryDelays.get(attempt).toMillis()))
                    .deadLetterExchange(exchange)
                    .deadLetterRoutingKey(queueName)
                    .build());
        }
        return new Declarables(declarables);
    }

    public static List<String> retryQueueNames(String queueName, List<Duration> retryDelays) {
        List<String> names = new ArrayList<>(retryDelays.size());
        for (int attempt = 1; attempt <= retryDelays.size(); attempt++) {
            names.add(queueName + ".retry." + attempt);
        }
        return List.copyOf(names);
    }
}
