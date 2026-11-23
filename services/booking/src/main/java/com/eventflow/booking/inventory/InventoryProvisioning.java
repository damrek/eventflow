package com.eventflow.booking.inventory;

import java.util.List;
import java.util.UUID;

import com.eventflow.booking.shared.domain.StateConflictException;

/**
 * Decides what a provisioning request means for an event: create the rows once, accept an
 * identical replay, and refuse anything that would silently change an offering that may already
 * have held units.
 */
public record InventoryProvisioning(boolean created, List<EventInventory> rows) {

    public static InventoryProvisioning plan(
            UUID eventId, List<InventoryItemSpec> requested, List<EventInventory> existing) {
        List<InventoryItemSpec> specs = InventoryItemSpec.validated(requested);
        if (existing.isEmpty()) {
            return new InventoryProvisioning(
                    true, specs.stream().map(spec -> EventInventory.provision(eventId, spec)).toList());
        }
        boolean identical = existing.size() == specs.size() && specs.stream().allMatch(spec -> matches(existing, spec));
        if (!identical) {
            throw new StateConflictException(
                    "INVENTORY_ALREADY_PROVISIONED",
                    "Inventory for event " + eventId + " was already provisioned with a different offer");
        }
        return new InventoryProvisioning(false, List.copyOf(existing));
    }

    private static boolean matches(List<EventInventory> existing, InventoryItemSpec spec) {
        return existing.stream().anyMatch(row -> row.matches(spec));
    }
}
