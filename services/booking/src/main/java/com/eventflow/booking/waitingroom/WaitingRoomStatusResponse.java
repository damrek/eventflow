package com.eventflow.booking.waitingroom;

import java.time.Instant;
import java.util.UUID;

/**
 * What the browser needs to render the waiting room: the position while waiting, and the
 * admission token plus its deadline once admitted. A token is only meaningful together with the
 * user and event it was issued for.
 */
public record WaitingRoomStatusResponse(
        UUID eventId,
        String userId,
        WaitingRoomStatus status,
        Integer position,
        long waitingCount,
        String token,
        Instant expiresAt) {

    static WaitingRoomStatusResponse waiting(UUID eventId, String userId, long position, long waitingCount) {
        return new WaitingRoomStatusResponse(
                eventId, userId, WaitingRoomStatus.WAITING, (int) position, waitingCount, null, null);
    }

    static WaitingRoomStatusResponse admitted(UUID eventId, String userId, String token, Instant expiresAt) {
        return new WaitingRoomStatusResponse(
                eventId, userId, WaitingRoomStatus.ADMITTED, null, 0, token, expiresAt);
    }

    static WaitingRoomStatusResponse notJoined(UUID eventId, String userId, long waitingCount) {
        return new WaitingRoomStatusResponse(
                eventId, userId, WaitingRoomStatus.NOT_JOINED, null, waitingCount, null, null);
    }
}
