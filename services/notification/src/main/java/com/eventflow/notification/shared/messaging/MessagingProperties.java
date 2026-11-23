package com.eventflow.notification.shared.messaging;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Messaging knobs of the consumer topology. Defaults match {@code application.yml} so a missing
 * property never disables retries or the DLQ.
 */
@ConfigurationProperties("eventflow.messaging")
public record MessagingProperties(String exchange, String deadLetterExchange, Retry retry) {

    public static final String DEFAULT_EXCHANGE = "eventflow.events";
    public static final String DEFAULT_DEAD_LETTER_EXCHANGE = "eventflow.events.dlx";
    private static final List<Duration> DEFAULT_DELAYS =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45));

    public MessagingProperties {
        exchange = exchange == null || exchange.isBlank() ? DEFAULT_EXCHANGE : exchange;
        deadLetterExchange =
                deadLetterExchange == null || deadLetterExchange.isBlank()
                        ? DEFAULT_DEAD_LETTER_EXCHANGE
                        : deadLetterExchange;
        retry = retry == null ? new Retry(null) : retry;
    }

    /** One retry queue per entry, consumed in order; the failure after the last one goes to the DLQ. */
    public record Retry(List<Duration> delays) {

        public Retry {
            if (delays == null
                    || delays.isEmpty()
                    || delays.stream().anyMatch(delay -> delay.isNegative() || delay.isZero())) {
                delays = DEFAULT_DELAYS;
            }
        }
    }
}
