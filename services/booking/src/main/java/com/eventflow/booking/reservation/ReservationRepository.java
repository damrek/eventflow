package com.eventflow.booking.reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @EntityGraph(attributePaths = "items")
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findDetailedById(@Param("id") UUID id);

    /**
     * Claims due reservations for this worker. {@code FOR UPDATE SKIP LOCKED} lets several
     * replicas scan at once without stepping on each other, and the row lock is held until the
     * transaction commits, so the same reservation is never expired twice.
     */
    @Query(value = """
            select id from reservation
             where status in ('HELD', 'PENDING_PAYMENT')
               and expires_at <= :now
             order by expires_at
             limit :batchSize
             for update skip locked
            """, nativeQuery = true)
    List<UUID> claimDue(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
