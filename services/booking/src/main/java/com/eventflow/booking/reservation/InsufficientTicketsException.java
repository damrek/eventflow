package com.eventflow.booking.reservation;

import java.util.UUID;

import com.eventflow.booking.shared.domain.BusinessRuleException;

/**
 * No conditional hold could reserve the requested units. Unprocessable (422): the request is
 * valid but the event cannot satisfy it, and the caller may retry with fewer units.
 */
public class InsufficientTicketsException extends BusinessRuleException {

    public InsufficientTicketsException(UUID ticketTypeId, int quantity) {
        super(
                "INSUFFICIENT_TICKETS",
                "Not enough tickets available for ticket type " + ticketTypeId + " to hold " + quantity + " unit(s)");
    }
}
