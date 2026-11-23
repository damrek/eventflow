package com.eventflow.notification.shared.domain;

/**
 * Shared guards that keep aggregates valid regardless of the caller. The API layer also uses
 * Bean Validation for request shape; these checks protect the domain itself.
 */
public final class DomainChecks {

    private DomainChecks() {
    }

    public static String text(String code, String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(code, field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BusinessRuleException(code, field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    public static void atLeast(String code, String field, int value, int minimum) {
        if (value < minimum) {
            throw new BusinessRuleException(code, field + " must be at least " + minimum + ", got " + value);
        }
    }

    public static void notNull(String code, String field, Object value) {
        if (value == null) {
            throw new BusinessRuleException(code, field + " is required");
        }
    }
}
