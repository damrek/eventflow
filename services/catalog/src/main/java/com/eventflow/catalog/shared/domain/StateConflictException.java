package com.eventflow.catalog.shared.domain;

/**
 * A request that conflicts with the current state of an aggregate. Mapped to HTTP 409.
 */
public class StateConflictException extends CatalogException {

    public StateConflictException(String code, String message) {
        super(code, message);
    }
}
