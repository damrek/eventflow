package com.eventflow.booking.waitingroom;

import java.util.UUID;

/**
 * Redis key and member names for the waiting room. Keeping them in one place makes the Lua
 * scripts and the Java side agree, and keeps the registry free of {@code KEYS}/{@code SCAN}
 * calls: the dispatcher only iterates events that actually have a queue.
 *
 * <ul>
 *   <li>{@code waiting:{eventId}} — sorted set of userIds scored by server sequence.</li>
 *   <li>{@code waiting-seq:{eventId}} — monotonic counter that assigns the next position.</li>
 *   <li>{@code waiting-token:{eventId}:{userId}} — admission token, TTL bounded.</li>
 *   <li>{@code waiting-events} — set of event ids with a non-empty queue.</li>
 * </ul>
 */
final class WaitingRoomKeys {

    static final String REGISTRY = "waiting-events";

    private WaitingRoomKeys() {
    }

    static String queue(UUID eventId) {
        return "waiting:" + eventId;
    }

    static String sequence(UUID eventId) {
        return "waiting-seq:" + eventId;
    }

    static String token(UUID eventId, String userId) {
        return tokenPrefix(eventId) + userId;
    }

    static String tokenPrefix(UUID eventId) {
        return "waiting-token:" + eventId + ":";
    }

    static UUID parseEventId(String member) {
        try {
            return UUID.fromString(member);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
