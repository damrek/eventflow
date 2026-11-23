package com.eventflow.booking.reservation;

import com.eventflow.booking.inventory.EventInventoryRepository;

/**
 * Single place that returns held units to availability. Both cancellation and expiration use it,
 * so the two paths cannot diverge; a zero-row release means the inventory and the reservation
 * disagree, which is a bug and must abort the transaction rather than create tickets.
 */
final class ReservationInventory {

    private ReservationInventory() {
    }

    static void releaseAll(EventInventoryRepository inventory, Reservation reservation) {
        for (ReservationItem item : reservation.getItems()) {
            int updated =
                    inventory.release(reservation.getEventId(), item.getTicketTypeId(), item.getQuantity());
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
}
