package com.eventflow.catalog.api.error;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Correlation id shared by the request filter, the error responses and (later) outbound
 * messages. Kept as a small utility so the value has a single name in one place.
 */
public final class TraceId {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Request-Id";

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
