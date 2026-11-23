package com.eventflow.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.eventflow.payment.provider.HttpPaymentGateway;
import com.eventflow.payment.provider.PaymentGateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * Builds the single circuit breaker that guards the provider, from our own properties. The
 * Resilience4j Spring Boot starter is deliberately not used: it targets the Boot 3 auto-config
 * model, while the core module is a plain library and stays compatible with Boot 4.
 */
@Configuration
public class PaymentProviderConfig {

    private static final String BREAKER_NAME = "payment-provider";

    @Bean
    public CircuitBreaker paymentProviderCircuitBreaker(PaymentProperties properties) {
        PaymentProperties.Provider.CircuitBreaker knobs = properties.provider().circuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(knobs.slidingWindowSize())
                .minimumNumberOfCalls(knobs.minimumNumberOfCalls())
                .failureRateThreshold(knobs.failureRateThreshold())
                .waitDurationInOpenState(knobs.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(knobs.permittedCallsInHalfOpenState())
                .build();
        return CircuitBreaker.of(BREAKER_NAME, config);
    }

    @Bean
    public PaymentGateway paymentGateway(
            RestClient.Builder restClientBuilder,
            PaymentProperties properties,
            CircuitBreaker paymentProviderCircuitBreaker) {
        return new HttpPaymentGateway(
                restClientBuilder.build(), paymentProviderCircuitBreaker, properties.provider().url());
    }
}
