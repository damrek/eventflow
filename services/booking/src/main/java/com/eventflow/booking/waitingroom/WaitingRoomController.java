package com.eventflow.booking.waitingroom;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eventflow.booking.shared.web.CurrentUserProvider;
import com.eventflow.booking.shared.web.rate.FixedWindowRateLimiter;
import com.eventflow.booking.shared.web.rate.RateLimitEndpoint;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * The event's waiting room. Joining is idempotent and rate limited; polling the status is not
 * limited because the browser needs it to render the position and the admission.
 */
@RestController
@RequestMapping("/api/waiting-room/{eventId}")
@RequiredArgsConstructor
public class WaitingRoomController {

    private final WaitingRoomService waitingRoom;
    private final CurrentUserProvider currentUser;
    private final FixedWindowRateLimiter rateLimiter;

    @PostMapping("/join")
    public WaitingRoomStatusResponse join(@PathVariable UUID eventId, HttpServletRequest request) {
        String userId = currentUser.userId(request);
        rateLimiter.check(RateLimitEndpoint.WAITING_ROOM_JOIN, userId);
        return waitingRoom.join(eventId, userId);
    }

    @GetMapping("/status")
    public WaitingRoomStatusResponse status(@PathVariable UUID eventId, HttpServletRequest request) {
        return waitingRoom.status(eventId, currentUser.userId(request));
    }
}
