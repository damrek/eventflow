package com.eventflow.booking.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import com.eventflow.booking.shared.domain.BusinessRuleException;
import com.eventflow.booking.shared.domain.DomainChecks;

/**
 * The offering of one ticket type for one event, as Booking knows it. Capacity is immutable
 * once provisioned; the mutable counters are moved only by the conditional statements in
 * {@link EventInventoryRepository}, never by a read followed by a write.
 */
public record InventoryItemSpec(UUID ticketTypeId, BigDecimal price, int capacity) {

    private static final int MAX_SCALE = 2;

    public InventoryItemSpec {
        DomainChecks.notNull("INVENTORY_TICKET_TYPE_REQUIRED", "Ticket type id", ticketTypeId);
        DomainChecks.atLeast("INVENTORY_CAPACITY_INVALID", "Ticket capacity", capacity, 1);
        price = normalizePrice(price);
    }

    /**
     * Request-level validation that the database alone cannot express before any row exists:
     * at least one item and one item per ticket type.
     */
    public static List<InventoryItemSpec> validated(List<InventoryItemSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new BusinessRuleException(
                    "INVENTORY_WITHOUT_TICKET_TYPES", "Inventory requires at least one ticket type");
        }
        long distinct = specs.stream().map(InventoryItemSpec::ticketTypeId).distinct().count();
        if (distinct != specs.size()) {
            throw new BusinessRuleException(
                    "INVENTORY_DUPLICATE_TICKET_TYPE", "Each ticket type may appear only once");
        }
        return List.copyOf(specs);
    }

    private static BigDecimal normalizePrice(BigDecimal price) {
        DomainChecks.notNull("INVENTORY_PRICE_REQUIRED", "Ticket price", price);
        if (price.signum() < 0) {
            throw new BusinessRuleException("INVENTORY_PRICE_INVALID", "Ticket price must not be negative");
        }
        if (price.stripTrailingZeros().scale() > MAX_SCALE) {
            throw new BusinessRuleException(
                    "INVENTORY_PRICE_SCALE_INVALID",
                    "Ticket price must have at most " + MAX_SCALE + " decimals");
        }
        return price.setScale(MAX_SCALE, RoundingMode.UNNECESSARY);
    }
}
