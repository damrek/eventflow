package com.eventflow.notification.notification;

/**
 * Kind of user-facing message. The value is stored, so it is stable; the copy in {@code content}
 * may change without rewriting history.
 */
public enum NotificationType {

    BOOKING_CONFIRMED("BookingConfirmed"),
    BOOKING_CANCELLED("BookingCancelled"),
    BOOKING_EXPIRED("BookingExpired");

    private final String eventType;

    NotificationType(String eventType) {
        this.eventType = eventType;
    }

    public String eventType() {
        return eventType;
    }

    /** @return the type for an incoming event, or {@code null} when this service ignores it. */
    public static NotificationType fromEventType(String eventType) {
        for (NotificationType type : values()) {
            if (type.eventType.equals(eventType)) {
                return type;
            }
        }
        return null;
    }
}
