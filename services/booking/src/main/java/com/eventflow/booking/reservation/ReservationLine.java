package com.eventflow.booking.reservation;

import java.math.BigDecimal;
import java.util.UUID;

import com.eventflow.booking.shared.domain.BusinessRuleException;
import com.eventflow.booking.shared.domain.DomainChecks;

/**
 * A priced reservation line. The unit price is a snapshot taken from inventory when the hold is
 * created, so a later catalog price change cannot alter an existing reservation.
 */
record ReservationLine(UUID ticketTypeId, int quantity, BigDecimal unitPrice) {

    ReservationLine {
        DomainChecks.notNull("RESERVATION_TICKET_TYPE_REQUIRED", "Ticket type id", ticketTypeId);
        DomainChecks.atLeast("RESERVATION_QUANTITY_INVALID", "Ticket quantity", quantity, 1);
        DomainChecks.notNull("RESERVATION_UNIT_PRICE_REQUIRED", "Unit price", unitPrice);
        if (unitPrice.signum() < 0) {
            throw new BusinessRuleException("RESERVATION_UNIT_PRICE_INVALID", "Unit price must not be negative");
        }
    }

    BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
