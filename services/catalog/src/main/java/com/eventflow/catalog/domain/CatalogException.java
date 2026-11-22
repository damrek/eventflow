package com.eventflow.catalog.domain;

/**
 * Base class for expected Catalog failures. Each failure carries a stable machine-readable
 * code that is exposed in the RFC 9457 problem response.
 */
public abstract class CatalogException extends RuntimeException {

    private final String code;

    protected CatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
