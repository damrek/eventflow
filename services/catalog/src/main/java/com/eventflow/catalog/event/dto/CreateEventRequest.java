package com.eventflow.catalog.event.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateEventRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull UUID venueId,
        @NotNull Instant startsAt,
        @NotEmpty @Size(max = 20) List<@Valid CreateTicketTypeRequest> ticketTypes) {
}
