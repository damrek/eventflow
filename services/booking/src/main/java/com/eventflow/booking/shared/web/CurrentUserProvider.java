package com.eventflow.booking.shared.web;

import org.springframework.stereotype.Component;

import com.eventflow.booking.shared.domain.InvalidRequestException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the synthetic demo user. This is <strong>not</strong> authentication: the header is
 * trusted on purpose because the MVP runs locally, and a real deployment replaces this bean
 * with a token-based provider. Every reservation use case takes the id from here so the
 * decision lives in one place.
 */
@Component
public class CurrentUserProvider {

    public static final String HEADER = "X-User-Id";
    private static final int MAX_LENGTH = 100;

    public String userId(HttpServletRequest request) {
        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("USER_ID_REQUIRED", HEADER + " header is required");
        }
        String userId = raw.trim();
        if (userId.length() > MAX_LENGTH) {
            throw new InvalidRequestException(
                    "USER_ID_INVALID", HEADER + " header must be at most " + MAX_LENGTH + " characters");
        }
        return userId;
    }
}
