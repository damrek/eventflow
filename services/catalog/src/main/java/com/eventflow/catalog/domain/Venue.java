package com.eventflow.catalog.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "venue")
public class Venue {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "address", nullable = false, length = 400)
    private String address;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    protected Venue() {
    }

    private Venue(UUID id, String name, String address, int capacity) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.capacity = capacity;
    }

    public static Venue create(String name, String address, int capacity) {
        Checks.atLeast("VENUE_CAPACITY_INVALID", "Venue capacity", capacity, 1);
        return new Venue(
                UUID.randomUUID(),
                Checks.text("VENUE_NAME_INVALID", "Venue name", name, 200),
                Checks.text("VENUE_ADDRESS_INVALID", "Venue address", address, 400),
                capacity);
    }

    /**
     * Maximum number of tickets that can physically be sold for this venue.
     */
    public int getCapacity() {
        return capacity;
    }

    public UUID getId() {
        return id;
    }

    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }
}
