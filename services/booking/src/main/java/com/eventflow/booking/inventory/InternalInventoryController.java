package com.eventflow.booking.inventory;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eventflow.booking.inventory.dto.AvailabilityResponse;
import com.eventflow.booking.inventory.dto.ProvisionInventoryRequest;
import com.eventflow.booking.inventory.dto.ProvisionTicketTypeRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Provisional provisioning endpoint: today Booking learns an event's offering through this
 * call; Phase 3 replaces the trigger with the {@code EventPublished} consumer. Kept under
 * {@code /api/internal} so it is not part of the public contract. See ADR 0007.
 */
@RestController
@RequestMapping("/api/internal/inventory/events/{eventId}")
@RequiredArgsConstructor
public class InternalInventoryController {

    private final InventoryService inventory;

    @PostMapping
    public ResponseEntity<AvailabilityResponse> provision(
            @PathVariable UUID eventId, @Valid @RequestBody ProvisionInventoryRequest request) {
        InventoryProvisioning result = inventory.provision(
                eventId, request.items().stream().map(ProvisionTicketTypeRequest::toSpec).toList());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(AvailabilityResponse.from(eventId, result.rows()));
    }
}
