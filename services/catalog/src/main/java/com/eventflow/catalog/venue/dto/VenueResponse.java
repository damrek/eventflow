package com.eventflow.catalog.venue.dto;

import java.util.UUID;

import com.eventflow.catalog.venue.Venue;

public record VenueResponse(UUID id, String name, String address, int capacity) {

    public static VenueResponse from(Venue venue) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getAddress(), venue.getCapacity());
    }
}
