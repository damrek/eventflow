package com.eventflow.simulator;

/** Provider answer: {@code APPROVED} or {@code REJECTED}, with its reference and optional reason. */
public record AuthorizationResponse(String outcome, String providerReference, String reason) {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
}
