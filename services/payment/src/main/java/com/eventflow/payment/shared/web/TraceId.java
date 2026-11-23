package com.eventflow.payment.shared.web;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Correlation id shared by the outbox producer and the logs. Payment has no public HTTP API, so
 * the value comes from the consumer request context when one exists.
 */
public final class TraceId {

    public static final String MDC_KEY = "traceId";

    private static final String ABSENT = "unavailable";

    private TraceId() {
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? ABSENT : value;
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
