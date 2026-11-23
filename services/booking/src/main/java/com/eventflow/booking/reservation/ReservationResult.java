package com.eventflow.booking.reservation;

import com.eventflow.booking.reservation.dto.ReservationResponse;

/**
 * What the service returns for a creation attempt: the status to answer with and the body,
 * which is the freshly built response or the snapshot stored by the first call with the same
 * idempotency key.
 */
record ReservationResult(int status, ReservationResponse body) {
}
