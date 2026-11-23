package com.eventflow.booking.shared.messaging;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Messaging knobs shared by the outbox relay and the consumer topology. Defaults match
 * {@code application.yml} so a missing property never disables delivery, retries or the DLQ.
 */
@ConfigurationProperties("eventflow.messaging")
public record MessagingProperties(String exchange, String deadLetterExchange, Outbox outbox, Retry retry) {

    public static final String DEFAULT_EXCHANGE = "eventflow.events";
    public static final String DEFAULT_DEAD_LETTER_EXCHANGE = "eventflow.events.dlx";
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);
    private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final List<Duration> DEFAULT_DELAYS =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45));

    public MessagingProperties {
        exchange = exchange == null || exchange.isBlank() ? DEFAULT_EXCHANGE : exchange;
        deadLetterExchange =
                deadLetterExchange == null || deadLetterExchange.isBlank()
                        ? DEFAULT_DEAD_LETTER_EXCHANGE
                        : deadLetterExchange;
        outbox = outbox == null ? new Outbox(null, 0, null) : outbox;
        retry = retry == null ? new Retry(null) : retry;
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

    /** One retry queue per entry, consumed in order; the failure after the last one goes to the DLQ. */
    public record Retry(List<Duration> delays) {

        public Retry {
            if (delays == null || delays.isEmpty() || delays.stream().anyMatch(delay -> delay.isNegative() || delay.isZero())) {
                delays = DEFAULT_DELAYS;
            }
        }
    }
}
