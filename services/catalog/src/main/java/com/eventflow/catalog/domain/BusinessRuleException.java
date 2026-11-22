package com.eventflow.catalog.domain;

/**
 * A well-formed request that violates a domain invariant. Mapped to HTTP 422.
 */
public class BusinessRuleException extends CatalogException {

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }
}
