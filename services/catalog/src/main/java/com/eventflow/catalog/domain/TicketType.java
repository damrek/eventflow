package com.eventflow.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "ticket_type",
        uniqueConstraints = @UniqueConstraint(name = "uk_ticket_type_event_name", columnNames = {"event_id", "name"}))
public class TicketType {

    private static final int MAX_SCALE = 2;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "initial_capacity", nullable = false)
    private int initialCapacity;

    protected TicketType() {
    }

    private TicketType(UUID id, Event event, String name, BigDecimal price, int initialCapacity) {
        this.id = id;
        this.event = event;
        this.name = name;
        this.price = price;
        this.initialCapacity = initialCapacity;
    }

    static TicketType create(Event event, String name, BigDecimal price, int capacity) {
        Checks.atLeast("TICKET_CAPACITY_INVALID", "Ticket capacity", capacity, 1);
        return new TicketType(
                UUID.randomUUID(),
                event,
                Checks.text("TICKET_NAME_INVALID", "Ticket type name", name, 100),
                normalizePrice(price),
                capacity);
    }

    private static BigDecimal normalizePrice(BigDecimal price) {
        if (price == null) {
            throw new BusinessRuleException("TICKET_PRICE_INVALID", "Ticket price is required");
        }
        if (price.signum() < 0) {
            throw new BusinessRuleException("TICKET_PRICE_INVALID", "Ticket price must not be negative");
        }
        BigDecimal stripped = price.stripTrailingZeros();
        if (stripped.scale() > MAX_SCALE) {
            throw new BusinessRuleException(
                    "TICKET_PRICE_SCALE_INVALID", "Ticket price must have at most " + MAX_SCALE + " decimals");
        }
        return price.setScale(MAX_SCALE, RoundingMode.UNNECESSARY);
    }

    public int getInitialCapacity() {
        return initialCapacity;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }
}
