import type { Reservation } from "../../api/booking";

/** The payment saga is asynchronous: poll while the payment service works and while a hold is live. */
export const PAYMENT_POLL_MS = 1500;
export const HELD_POLL_MS = 5000;

/**
 * Terminal states stop polling. `HELD` keeps polling because only the backend can expire a hold;
 * the countdown never decides a status on its own.
 */
export function reservationPollInterval(reservation: Reservation | undefined): number | false {
  if (!reservation) return false;
  switch (reservation.status) {
    case "PENDING_PAYMENT":
      return PAYMENT_POLL_MS;
    case "HELD":
      return HELD_POLL_MS;
    default:
      return false;
  }
}
