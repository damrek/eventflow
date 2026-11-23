package com.eventflow.notification.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.eventflow.notification.notification.Notification;
import com.eventflow.notification.notification.NotificationStatus;
import com.eventflow.notification.notification.NotificationType;

/**
 * Public representation of a mailbox entry. Mapping is explicit so the entity can change without
 * changing the REST contract.
 */
public record NotificationResponse(
        UUID id,
        String userId,
        NotificationType type,
        String content,
        NotificationStatus status,
        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getUserId(),
                notification.getType(),
                notification.getContent(),
                notification.getStatus(),
                notification.getCreatedAt());
    }
}
