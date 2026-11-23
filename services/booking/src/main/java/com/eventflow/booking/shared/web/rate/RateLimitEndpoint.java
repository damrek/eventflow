package com.eventflow.booking.shared.web.rate;

/**
 * Endpoints protected by the distributed rate limit. The key is stable because it is part of the
 * Redis key, while the label is what the error message shows.
 */
public enum RateLimitEndpoint {

    WAITING_ROOM_JOIN("waiting-room-join"),
    RESERVATION_CREATE("reservation-create");

    private final String key;

    RateLimitEndpoint(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
