package com.eventflow.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateEventCommand(
        String name,
        UUID venueId,
        Instant startsAt,
        List<CreateTicketTypeCommand> ticketTypes) {
}
