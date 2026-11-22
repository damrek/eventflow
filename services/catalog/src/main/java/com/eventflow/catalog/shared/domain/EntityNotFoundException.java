package com.eventflow.catalog.shared.domain;

import java.util.UUID;

/**
 * A referenced aggregate does not exist. Mapped to HTTP 404.
 */
public class EntityNotFoundException extends CatalogException {

    private EntityNotFoundException(String code, String message) {
        super(code, message);
    }

    public static EntityNotFoundException of(String code, String entity, UUID id) {
        return new EntityNotFoundException(code, entity + " " + id + " was not found");
    }
}
