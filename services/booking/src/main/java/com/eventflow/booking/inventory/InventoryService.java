package com.eventflow.booking.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.booking.shared.domain.EntityNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Owns the inventory slice. Provisioning is idempotent by offer: the same payload is a no-op,
 * a different one is a conflict. The only trigger is the {@code EventPublished} consumer
 * (ADR 0009), which calls {@link #provision} inside its inbox transaction.
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final EventInventoryRepository inventory;

    @Transactional
    public InventoryProvisioning provision(UUID eventId, List<InventoryItemSpec> requested) {
        InventoryProvisioning plan =
                InventoryProvisioning.plan(eventId, requested, inventory.findByEventIdOrderByTicketTypeIdAsc(eventId));
        if (plan.created()) {
            inventory.saveAll(plan.rows());
        }
        return plan;
    }

    @Transactional(readOnly = true)
    public List<EventInventory> availability(UUID eventId) {
        List<EventInventory> rows = inventory.findByEventIdOrderByTicketTypeIdAsc(eventId);
        if (rows.isEmpty()) {
            throw EntityNotFoundException.of("INVENTORY_NOT_FOUND", "Inventory for event", eventId);
        }
        return rows;
    }
}
