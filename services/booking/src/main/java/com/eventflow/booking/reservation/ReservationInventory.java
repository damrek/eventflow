package com.eventflow.booking.reservation;

import com.eventflow.booking.inventory.EventInventoryRepository;

/**
 * Single place that moves held units out of the hold, so cancellation, expiration, payment
 * failure and confirmation cannot diverge. A zero-row statement means the inventory and the
 * reservation disagree, which is a bug and must abort the transaction rather than create or
 * destroy tickets.
 */
final class ReservationInventory {

    private ReservationInventory() {
    }

    static void releaseAll(EventInventoryRepository inventory, Reservation reservation) {
        for (ReservationItem item : reservation.getItems()) {
            int updated = inventory.release(
                    reservation.getEventId(), item.getTicketTypeId(), item.getQuantity());
            if (updated != 1) {
                throw new IllegalStateException("Inventory for event "
                        + reservation.getEventId()
                        + " and ticket type "
                        + item.getTicketTypeId()
                        + " does not hold "
                        + item.getQuantity()
                        + " unit(s); refusing to release reservation "
                        + reservation.getId());
            }
        }
    }

    static void confirmAll(EventInventoryRepository inventory, Reservation reservation) {
        for (ReservationItem item : reservation.getItems()) {
            int updated = inventory.confirm(
                    reservation.getEventId(), item.getTicketTypeId(), item.getQuantity());
            if (updated != 1) {
                throw new IllegalStateException("Inventory for event "
                        + reservation.getEventId()
                        + " and ticket type "
                        + item.getTicketTypeId()
                        + " does not hold "
                        + item.getQuantity()
                        + " unit(s); refusing to confirm reservation "
                        + reservation.getId());
            }
        }
    }
}
