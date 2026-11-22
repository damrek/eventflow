package com.eventflow.catalog.application;

import java.math.BigDecimal;

public record CreateTicketTypeCommand(String name, BigDecimal price, int capacity) {
}
