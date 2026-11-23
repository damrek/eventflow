package com.eventflow.simulator;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long the "slow provider" scenario waits before answering, so callers configured with a
 * shorter read timeout observe a real timeout while callers without one still get an approval.
 */
@ConfigurationProperties("simulator")
public record SimulatorProperties(Duration timeoutDelay) {

    private static final Duration DEFAULT_TIMEOUT_DELAY = Duration.ofSeconds(5);

    public SimulatorProperties {
        if (timeoutDelay == null || timeoutDelay.isNegative() || timeoutDelay.isZero()) {
            timeoutDelay = DEFAULT_TIMEOUT_DELAY;
        }
    }
}
