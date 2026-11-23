package com.eventflow.booking.inventory.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record ProvisionInventoryRequest(@NotEmpty @Valid List<ProvisionTicketTypeRequest> items) {
}
