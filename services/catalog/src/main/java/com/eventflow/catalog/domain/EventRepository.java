package com.eventflow.catalog.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

    @EntityGraph(attributePaths = {"venue", "ticketTypes"})
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findDetailedById(@Param("id") UUID id);

    @EntityGraph(attributePaths = "venue")
    @Query("""
            select e from Event e
            where e.status = :status
              and e.startsAt >= :from
              and e.startsAt < :to
            """)
    Page<Event> findForListing(
            @Param("status") EventStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
