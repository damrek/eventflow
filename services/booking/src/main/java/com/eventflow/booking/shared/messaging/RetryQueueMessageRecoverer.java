package com.eventflow.booking.shared.messaging;

import java.util.List;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

import com.eventflow.booking.shared.web.TraceId;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Moves a failing delivery to the next retry queue, or rejects it for the DLQ once the configured
 * attempts are spent. The attempt number travels in a message header this recoverer writes, so it
 * survives the republish, a broker restart and multiple replicas: the retry history is the
 * message's, never a consumer's local state.
 */
@Slf4j
@RequiredArgsConstructor
public class RetryQueueMessageRecoverer implements MessageRecoverer {

    private final RabbitTemplate rabbitTemplate;
    private final String queueName;
    private final List<String> retryQueues;

    @Override
    public void recover(Message message, Throwable cause) {
        // A permanent failure is not retryable at all: rejecting it here sends it to the DLQ
        // instead of spending the retry budget on a message that can never succeed.
        if (cause instanceof AmqpRejectAndDontRequeueException permanent) {
            throw permanent;
        }
        int attempts = retryAttempts(message);
        if (attempts >= retryQueues.size()) {
            log.error("Event {} exhausted {} retries on {}; rejecting to the DLQ [traceId={}]",
                    message.getMessageProperties().getMessageId(), attempts, queueName, TraceId.current(), cause);
            throw new AmqpRejectAndDontRequeueException(
                    "Retries exhausted for " + queueName + " after " + attempts + " attempt(s)", cause);
        }
        String next = retryQueues.get(attempts);
        rabbitTemplate.send("", next, MessageBuilder.fromMessage(message)
                .setHeader(RetryTopology.ATTEMPT_HEADER, attempts + 1)
                .build());
        log.warn("Event {} failed on {}; retry {}/{} scheduled in {} [traceId={}]",
                message.getMessageProperties().getMessageId(), queueName, attempts + 1, retryQueues.size(), next,
                TraceId.current());
    }

    private int retryAttempts(Message message) {
        Object value = message.getMessageProperties().getHeaders().get(RetryTopology.ATTEMPT_HEADER);
        return value instanceof Number number ? number.intValue() : 0;
    }
}
