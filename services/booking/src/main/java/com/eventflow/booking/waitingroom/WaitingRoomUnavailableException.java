package com.eventflow.booking.waitingroom;

import com.eventflow.booking.shared.domain.BookingException;

/**
 * The waiting room cannot be read right now because Redis is unreachable. Join and status answer
 * this instead of pretending the user has a position. Mapped to HTTP 503; reservation creation
 * does not answer it: the admission gate fails open so sales keep working.
 */
public class WaitingRoomUnavailableException extends BookingException {

    public static final String CODE = "WAITING_ROOM_UNAVAILABLE";

    public WaitingRoomUnavailableException(Throwable cause) {
        super(CODE, "The waiting room is temporarily unavailable");
        initCause(cause);
    }
}
