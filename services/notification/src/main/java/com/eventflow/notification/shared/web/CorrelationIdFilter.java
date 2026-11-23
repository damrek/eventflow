package com.eventflow.notification.shared.web;

import java.io.IOException;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Accepts a caller-provided correlation id (validated against a safe character set) or
 * generates one, exposes it as {@code traceId} in the MDC and echoes it back in the
 * response so a client can correlate an error with server logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String candidate = request.getHeader(TraceId.HEADER);
        String traceId = candidate != null && SAFE_ID.matcher(candidate).matches() ? candidate : TraceId.generate();

        MDC.put(TraceId.MDC_KEY, traceId);
        response.setHeader(TraceId.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceId.MDC_KEY);
        }
    }
}
