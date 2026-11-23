package com.eventflow.booking.waitingroom;

import com.eventflow.booking.shared.domain.BookingException;

/**
 * A reservation was attempted for an event whose waiting room is active without a live admission
 * token for that user. The client must join, wait for admission and retry. Mapped to HTTP 403.
 */
public class NotAdmittedException extends BookingException {

    public static final String CODE = "NOT_ADMITTED";

    public NotAdmittedException(String message) {
        super(CODE, message);
    }
}
