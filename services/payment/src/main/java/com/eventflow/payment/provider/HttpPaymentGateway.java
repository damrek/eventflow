package com.eventflow.payment.provider;

import java.net.URI;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP implementation of {@link PaymentGateway}. Every call goes through the circuit breaker, so
 * a provider that is timing out or answering {@code 5xx} stops being called once the failure rate
 * crosses the threshold and the message is retried later instead of blocking the consumer.
 *
 * <p>Connect and read timeouts come from {@code spring.http.clients.*}; the "slow" simulator
 * scenario therefore produces a real client timeout, not a sleeping test.
 */
@Slf4j
@RequiredArgsConstructor
public class HttpPaymentGateway implements PaymentGateway {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final URI endpoint;

    @Override
    public ProviderAuthorizationResponse authorize(ProviderAuthorizationRequest request) {
        try {
            ProviderAuthorizationResponse response = circuitBreaker.executeSupplier(() -> restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ProviderAuthorizationResponse.class));
            if (response == null || response.outcome() == null) {
                throw new ProviderUnavailableException("Payment provider returned an empty authorization");
            }
            return response;
        } catch (CallNotPermittedException open) {
            log.warn("Payment provider circuit is open; authorization for payment {} is deferred",
                    request.paymentId());
            throw new ProviderUnavailableException("Payment provider circuit is open", open);
        } catch (RestClientException failed) {
            log.warn("Payment provider call failed for payment {}: {}", request.paymentId(), failed.getMessage());
            throw new ProviderUnavailableException("Payment provider call failed: " + failed.getMessage(), failed);
        }
    }
}
