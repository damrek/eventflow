package com.eventflow.simulator;

/** Body of the simulated provider outage; Payment only needs the status code to retry later. */
public record ProviderError(String code, String detail) {
}
