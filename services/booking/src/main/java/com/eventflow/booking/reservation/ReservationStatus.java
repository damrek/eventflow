package com.eventflow.booking.reservation;

/**
 * Reservation lifecycle reachable in this phase. {@code PENDING_PAYMENT}, {@code CONFIRMED} and
 * {@code PAYMENT_FAILED} arrive with the payment saga (Phase 4), together with the migration
 * that widens the database {@code CHECK}.
 */
public enum ReservationStatus {
    HELD,
    EXPIRED,
    CANCELLED
}
