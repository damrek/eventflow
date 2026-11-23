package com.eventflow.notification.notification;

/**
 * Mailbox state of a message. Reading is a UI concern (Phase 5); until then every notification
 * arrives {@code UNREAD}.
 */
public enum NotificationStatus {
    UNREAD,
    READ
}
