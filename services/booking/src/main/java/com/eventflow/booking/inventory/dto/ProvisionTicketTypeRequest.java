package com.eventflow.booking.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.eventflow.booking.inventory.InventoryItemSpec;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ProvisionTicketTypeRequest(
        @NotNull UUID ticketTypeId,
        @NotNull @DecimalMin(value = "0.00", message = "must not be negative") BigDecimal price,
        @Min(value = 1, message = "must be at least 1") int capacity) {

    public InventoryItemSpec toSpec() {
        return new InventoryItemSpec(ticketTypeId, price, capacity);
    }
}
