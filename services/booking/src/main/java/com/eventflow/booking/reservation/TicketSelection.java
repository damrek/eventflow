package com.eventflow.booking.reservation;

import java.util.List;
import java.util.UUID;

import com.eventflow.booking.shared.domain.BusinessRuleException;
import com.eventflow.booking.shared.domain.DomainChecks;

/**
 * What a caller asks for: a ticket type and a quantity. It carries no price; the price is read
 * from the inventory at reservation time so the client cannot influence what it pays.
 */
public record TicketSelection(UUID ticketTypeId, int quantity) {

    public TicketSelection {
        DomainChecks.notNull("RESERVATION_TICKET_TYPE_REQUIRED", "Ticket type id", ticketTypeId);
        DomainChecks.atLeast("RESERVATION_QUANTITY_INVALID", "Ticket quantity", quantity, 1);
    }

    /**
     * Request-level rules the aggregate cannot see before prices exist: at least one line and
     * one line per ticket type, so a duplicate cannot hold the same units twice.
     */
    public static List<TicketSelection> validated(List<TicketSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new BusinessRuleException("RESERVATION_WITHOUT_ITEMS", "A reservation requires at least one item");
        }
        long distinct = selections.stream().map(TicketSelection::ticketTypeId).distinct().count();
        if (distinct != selections.size()) {
            throw new BusinessRuleException(
                    "RESERVATION_DUPLICATE_TICKET_TYPE", "Each ticket type may appear only once");
        }
        return List.copyOf(selections);
    }
}
