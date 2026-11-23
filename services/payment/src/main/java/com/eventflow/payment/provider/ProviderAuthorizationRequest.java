package com.eventflow.payment.provider;

import java.math.BigDecimal;
import java.util.UUID;

/** What Payment sends to the provider: no card data, only what an authorization needs. */
public record ProviderAuthorizationRequest(
        UUID paymentId, UUID reservationId, BigDecimal amount, String currency) {
}
