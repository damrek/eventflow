package com.eventflow.payment.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider and resilience knobs. Defaults match {@code application.yml} so a missing property
 * never disables the circuit breaker or turns the provider URL into {@code null}.
 */
@ConfigurationProperties("payment")
public record PaymentProperties(Provider provider) {

    private static final String DEFAULT_PROVIDER_URL = "http://localhost:8085/simulator/authorizations";

    public PaymentProperties {
        provider = provider == null ? new Provider(null, null) : provider;
    }

    public record Provider(URI url, CircuitBreaker circuitBreaker) {

        public Provider {
            url = url == null ? URI.create(DEFAULT_PROVIDER_URL) : url;
            circuitBreaker = circuitBreaker == null ? new CircuitBreaker(0, 0, 0, null, 0) : circuitBreaker;
        }

        /**
         * Circuit breaker shape. The window and the minimum number of calls are low enough to
         * open in a demo without waiting a minute; the open state is short so a recovered
         * provider is retried by a later delivery.
         */
        public record CircuitBreaker(
                int slidingWindowSize,
                int minimumNumberOfCalls,
                float failureRateThreshold,
                Duration waitDurationInOpenState,
                int permittedCallsInHalfOpenState) {

            private static final int DEFAULT_WINDOW = 10;
            private static final int DEFAULT_MIN_CALLS = 5;
            private static final float DEFAULT_FAILURE_RATE = 50f;
            private static final Duration DEFAULT_OPEN_WAIT = Duration.ofSeconds(10);
            private static final int DEFAULT_HALF_OPEN_CALLS = 3;

            public CircuitBreaker {
                if (slidingWindowSize < 1) {
                    slidingWindowSize = DEFAULT_WINDOW;
                }
                if (minimumNumberOfCalls < 1) {
                    minimumNumberOfCalls = DEFAULT_MIN_CALLS;
                }
                if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
                    failureRateThreshold = DEFAULT_FAILURE_RATE;
                }
                if (waitDurationInOpenState == null
                        || waitDurationInOpenState.isNegative()
                        || waitDurationInOpenState.isZero()) {
                    waitDurationInOpenState = DEFAULT_OPEN_WAIT;
                }
                if (permittedCallsInHalfOpenState < 1) {
                    permittedCallsInHalfOpenState = DEFAULT_HALF_OPEN_CALLS;
                }
            }
        }
    }
}
