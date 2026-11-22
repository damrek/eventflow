package com.eventflow.catalog.application;

public record CreateVenueCommand(String name, String address, int capacity) {
}
