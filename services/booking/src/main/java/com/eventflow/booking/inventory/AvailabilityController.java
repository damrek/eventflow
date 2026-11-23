package com.eventflow.booking.inventory;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eventflow.booking.inventory.dto.AvailabilityResponse;

import lombok.RequiredArgsConstructor;

/**
 * Public read of live inventory. Values come from PostgreSQL, which is the only authority on
 * what can be held; Redis is not consulted here.
 */
@RestController
@RequestMapping("/api/events/{eventId}/availability")
@RequiredArgsConstructor
public class AvailabilityController {

    private final InventoryService inventory;

    @GetMapping
    public AvailabilityResponse get(@PathVariable UUID eventId) {
        return AvailabilityResponse.from(eventId, inventory.availability(eventId));
    }
}
