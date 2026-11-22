package com.eventflow.catalog.domain;

import java.math.BigDecimal;

/**
 * Value object used to create an event together with its ticket types in one aggregate
 * operation.
 */
public record TicketTypeSpec(String name, BigDecimal price, int capacity) {
}
