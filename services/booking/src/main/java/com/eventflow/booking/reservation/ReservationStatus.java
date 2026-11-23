package com.eventflow.booking.reservation;

/**
 * Reservation lifecycle, including the payment steps of the saga (Phase 4):
 * {@code HELD -> PENDING_PAYMENT -> CONFIRMED | PAYMENT_FAILED}, plus {@code HELD} or
 * {@code PENDING_PAYMENT} expiring when the hold deadline passes.
 */
public enum ReservationStatus {
    HELD,
    PENDING_PAYMENT,
    CONFIRMED,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED
}
