package com.eventflow.catalog.shared.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Messaging knobs shared by the outbox relay and the declarables. Defaults match
 * {@code application.yml} so a missing property never disables delivery or floods the broker.
 */
@ConfigurationProperties("eventflow.messaging")
public record MessagingProperties(String exchange, Outbox outbox) {

    public static final String DEFAULT_EXCHANGE = "eventflow.events";
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);
    private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_BATCH_SIZE = 50;

    public MessagingProperties {
        exchange = exchange == null || exchange.isBlank() ? DEFAULT_EXCHANGE : exchange;
        outbox = outbox == null ? new Outbox(null, 0, null) : outbox;
    }

    public record Outbox(Duration interval, int batchSize, Duration confirmTimeout) {

        public Outbox {
            if (interval == null || interval.isNegative() || interval.isZero()) {
                interval = DEFAULT_INTERVAL;
            }
            if (batchSize < 1) {
                batchSize = DEFAULT_BATCH_SIZE;
            }
            if (confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero()) {
                confirmTimeout = DEFAULT_CONFIRM_TIMEOUT;
            }
        }
    }
}
