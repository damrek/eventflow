package com.eventflow.notification.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.eventflow.notification.shared.domain.DomainChecks;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One message in a user's mailbox. {@code eventId} is the source event and is unique, so the
 * database itself refuses a second row for the same event even if the inbox were bypassed.
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private NotificationType type;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static Notification of(
            NotificationType type, String userId, String content, UUID eventId, Instant createdAt) {
        Notification notification = new Notification();
        notification.id = UUID.randomUUID();
        notification.userId = DomainChecks.text("NOTIFICATION_USER_INVALID", "Notification user", userId, 100);
        notification.type = type;
        notification.content = DomainChecks.text("NOTIFICATION_CONTENT_INVALID", "Notification content", content, 500);
        notification.status = NotificationStatus.UNREAD;
        DomainChecks.notNull("NOTIFICATION_EVENT_REQUIRED", "Source event", eventId);
        notification.eventId = eventId;
        DomainChecks.notNull("NOTIFICATION_CREATED_AT_REQUIRED", "Creation instant", createdAt);
        notification.createdAt = createdAt;
        return notification;
    }
}
