package com.eventflow.notification.notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.eventflow.notification.notification.dto.NotificationResponse;
import com.eventflow.notification.shared.web.PageResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;

    /**
     * {@code userId} is required and validated by the service so a missing value answers
     * {@code USER_ID_REQUIRED} instead of a generic validation error.
     */
    @GetMapping
    public PageResponse<NotificationResponse> list(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return notifications.list(userId, page, size);
    }
}
