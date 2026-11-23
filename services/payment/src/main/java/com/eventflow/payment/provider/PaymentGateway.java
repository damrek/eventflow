package com.eventflow.payment.provider;

/** Authorizations of one payment against the external provider. */
public interface PaymentGateway {

    ProviderAuthorizationResponse authorize(ProviderAuthorizationRequest request);
}
