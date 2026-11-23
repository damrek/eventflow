package com.eventflow.notification.shared.domain;

import java.util.UUID;

/**
 * A referenced aggregate does not exist. Mapped to HTTP 404; also used to hide resources the
 * caller does not own.
 */
public class EntityNotFoundException extends NotificationException {

    private EntityNotFoundException(String code, String message) {
        super(code, message);
    }

    public static EntityNotFoundException of(String code, String entity, UUID id) {
        return new EntityNotFoundException(code, entity + " " + id + " was not found");
    }

    public static EntityNotFoundException of(String code, String entity, String key) {
        return new EntityNotFoundException(code, entity + " " + key + " was not found");
    }
}
