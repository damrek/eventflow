package com.eventflow.notification.notification;

import java.time.Clock;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.notification.notification.dto.NotificationResponse;
import com.eventflow.notification.shared.domain.InvalidRequestException;
import com.eventflow.notification.shared.web.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * Mailbox use cases. A notification is written only from a consumed event, inside the consumer
 * transaction; there is no HTTP endpoint that creates one.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notifications;
    private final Clock clock;

    @Transactional
    public Notification record(NotificationType type, String userId, String content, UUID sourceEventId) {
        return notifications.save(Notification.of(type, userId, content, sourceEventId, clock.instant()));
    }

    /**
     * A user's mailbox, newest first. The user comes from the query string as the API contract
     * specifies; the demo identity is not authentication.
     */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(String userId, int page, int size) {
        if (userId == null || userId.isBlank()) {
            throw new InvalidRequestException("USER_ID_REQUIRED", "'userId' query parameter is required");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException(
                    "INVALID_PAGINATION", "'page' must be at least 0 and 'size' between 1 and " + MAX_PAGE_SIZE);
        }
        Page<Notification> result =
                notifications.findByUserIdOrderByCreatedAtDesc(userId.trim(), PageRequest.of(page, size));
        return PageResponse.of(result, NotificationResponse::from);
    }

    /** Copy of one mailbox entry; kept next to the type it belongs to. */
    public String contentFor(NotificationType type, UUID reservationId, UUID eventId) {
        return switch (type) {
            case BOOKING_CONFIRMED ->
                    "Reservation " + reservationId + " for event " + eventId + " is confirmed.";
            case BOOKING_CANCELLED ->
                    "Reservation " + reservationId + " for event " + eventId + " was cancelled; its tickets were released.";
            case BOOKING_EXPIRED ->
                    "Reservation " + reservationId + " for event " + eventId + " expired; its tickets were released.";
        };
    }
}
