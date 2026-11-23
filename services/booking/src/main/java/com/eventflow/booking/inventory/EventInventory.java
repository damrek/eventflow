package com.eventflow.booking.inventory;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.eventflow.booking.shared.domain.BusinessRuleException;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Inventory row for one ticket type of one event.
 *
 * <p>{@code capacity = available + held + sold} is enforced by a database {@code CHECK}. The
 * counters are moved with atomic conditional updates ({@link EventInventoryRepository}) because
 * a read-then-write would let two requests sell the same seat; that is also why this entity has
 * no {@code @Version} field — JPA optimistic locking would apply to the entity, not to those
 * statements. See ADR 0006.
 */
@Entity
@Table(
        name = "event_inventory",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_event_inventory_event_ticket",
                        columnNames = {"event_id", "ticket_type_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventInventory {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "ticket_type_id", nullable = false)
    private UUID ticketTypeId;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "available", nullable = false)
    private int available;

    @Column(name = "held", nullable = false)
    private int held;

    @Column(name = "sold", nullable = false)
    private int sold;

    static EventInventory provision(UUID eventId, InventoryItemSpec spec) {
        if (eventId == null) {
            throw new BusinessRuleException("INVENTORY_EVENT_REQUIRED", "Inventory requires an event");
        }
        EventInventory inventory = new EventInventory();
        inventory.id = UUID.randomUUID();
        inventory.eventId = eventId;
        inventory.ticketTypeId = spec.ticketTypeId();
        inventory.price = spec.price();
        inventory.capacity = spec.capacity();
        inventory.available = spec.capacity();
        inventory.held = 0;
        inventory.sold = 0;
        return inventory;
    }

    public boolean matches(InventoryItemSpec spec) {
        return ticketTypeId.equals(spec.ticketTypeId())
                && capacity == spec.capacity()
                && price.compareTo(spec.price()) == 0;
    }
}
