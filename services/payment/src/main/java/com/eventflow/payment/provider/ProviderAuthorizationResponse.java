package com.eventflow.payment.provider;

/** Provider answer. An already-approved authorization is never asked again for the same payment. */
public record ProviderAuthorizationResponse(ProviderOutcome outcome, String providerReference, String reason) {

    public boolean approved() {
        return outcome == ProviderOutcome.APPROVED;
    }

    public enum ProviderOutcome {
        APPROVED,
        REJECTED
    }
}
