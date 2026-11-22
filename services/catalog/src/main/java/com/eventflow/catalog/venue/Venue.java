package com.eventflow.catalog.venue;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.eventflow.catalog.shared.domain.DomainChecks;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "venue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Venue {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "address", nullable = false, length = 400)
    private String address;

    /** Maximum number of tickets that can physically be sold for this venue. */
    @Column(name = "capacity", nullable = false)
    private int capacity;

    private Venue(UUID id, String name, String address, int capacity) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.capacity = capacity;
    }

    public static Venue create(String name, String address, int capacity) {
        DomainChecks.atLeast("VENUE_CAPACITY_INVALID", "Venue capacity", capacity, 1);
        return new Venue(
                UUID.randomUUID(),
                DomainChecks.text("VENUE_NAME_INVALID", "Venue name", name, 200),
                DomainChecks.text("VENUE_ADDRESS_INVALID", "Venue address", address, 400),
                capacity);
    }
}
