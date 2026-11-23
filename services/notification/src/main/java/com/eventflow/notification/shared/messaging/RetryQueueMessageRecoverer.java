package com.eventflow.notification.shared.messaging;

import java.util.List;
import java.util.Map;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

import com.eventflow.notification.shared.web.TraceId;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Moves a failing delivery to the next retry queue, or rejects it for the DLQ once the configured
 * attempts are spent. The attempt number is not local state: it is derived from the broker's
 * {@code x-death} header, so restarts and multiple replicas agree on where a message is in its
 * retry history.
 */
@Slf4j
@RequiredArgsConstructor
public class RetryQueueMessageRecoverer implements MessageRecoverer {

    private final RabbitTemplate rabbitTemplate;
    private final String queueName;
    private final List<String> retryQueues;

    @Override
    public void recover(Message message, Throwable cause) {
        int attempts = retryAttempts(message);
        if (attempts >= retryQueues.size()) {
            log.error("Event {} exhausted {} retries on {}; rejecting to the DLQ [traceId={}]",
                    message.getMessageProperties().getMessageId(), attempts, queueName, TraceId.current(), cause);
            throw new AmqpRejectAndDontRequeueException(
                    "Retries exhausted for " + queueName + " after " + attempts + " attempt(s)", cause);
        }
        String next = retryQueues.get(attempts);
        rabbitTemplate.send("", next, message);
        log.warn("Event {} failed on {}; retry {}/{} scheduled in {} [traceId={}]",
                message.getMessageProperties().getMessageId(), queueName, attempts + 1, retryQueues.size(), next,
                TraceId.current());
    }

    private int retryAttempts(Message message) {
        Object deaths = message.getMessageProperties().getHeaders().get("x-death");
        if (!(deaths instanceof List<?> entries)) {
            return 0;
        }
        int attempts = 0;
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> death && retryQueues.contains(String.valueOf(death.get("queue")))) {
                Object count = death.get("count");
                attempts += count instanceof Number number ? number.intValue() : 1;
            }
        }
        return attempts;
    }
}
