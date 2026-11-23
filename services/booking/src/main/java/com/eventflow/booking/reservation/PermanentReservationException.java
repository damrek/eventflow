package com.eventflow.booking.reservation;

/**
 * A payment result that no retry can make applicable: the reservation does not exist. The
 * listener rejects it without requeue so it ends in the DLQ instead of burning the retry budget.
 */
public class PermanentReservationException extends RuntimeException {

    public PermanentReservationException(String message) {
        super(message);
    }
}
