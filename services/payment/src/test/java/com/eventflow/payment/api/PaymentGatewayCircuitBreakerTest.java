package com.eventflow.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.eventflow.payment.provider.PaymentGateway;
import com.eventflow.payment.provider.ProviderAuthorizationRequest;
import com.eventflow.payment.provider.ProviderUnavailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Gate of the circuit breaker: after the configured minimum of failed calls the breaker opens and
 * the next authorization fails fast, without reaching the provider. That is what keeps a dead
 * provider from being hammered while the broker retries the message.
 */
class PaymentGatewayCircuitBreakerTest extends PaymentIntegrationTest {

    private static final int MINIMUM_CALLS = 5;

    @Autowired
    private PaymentGateway gateway;

    @Autowired
    private CircuitBreaker paymentProviderCircuitBreaker;

    @Test
    void opensAfterRepeatedProviderFailuresAndFailsFast() {
        PROVIDER.always(FakePaymentProvider.Response.UNAVAILABLE);

        for (int call = 0; call < MINIMUM_CALLS; call++) {
            assertThatThrownBy(() -> gateway.authorize(request()))
                    .isInstanceOf(ProviderUnavailableException.class);
        }

        assertThat(paymentProviderCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(PROVIDER.calls()).isEqualTo(MINIMUM_CALLS);

        assertThatThrownBy(() -> gateway.authorize(request()))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThat(PROVIDER.calls()).isEqualTo(MINIMUM_CALLS);
    }

    private static ProviderAuthorizationRequest request() {
        return new ProviderAuthorizationRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("110.00"), "EUR");
    }
}
