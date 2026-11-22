package com.eventflow.catalog.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, UUID> {
}
