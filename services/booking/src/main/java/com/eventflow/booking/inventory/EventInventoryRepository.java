package com.eventflow.booking.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventInventoryRepository extends JpaRepository<EventInventory, UUID> {

    List<EventInventory> findByEventIdOrderByTicketTypeIdAsc(UUID eventId);

    Optional<EventInventory> findByEventIdAndTicketTypeId(UUID eventId, UUID ticketTypeId);

    /**
     * Moves units {@code available -> held} only when they exist. The condition is evaluated by
     * PostgreSQL inside the row lock, so 0 updated rows means "not enough stock", never a
     * stale read. Callers sort by ticket type before holding to keep lock order stable.
     */
    @Modifying
    @Query(value = """
            update event_inventory
               set available = available - :quantity,
                   held = held + :quantity
             where event_id = :eventId
               and ticket_type_id = :ticketTypeId
               and available >= :quantity
            """, nativeQuery = true)
    int hold(
            @Param("eventId") UUID eventId,
            @Param("ticketTypeId") UUID ticketTypeId,
            @Param("quantity") int quantity);

    /**
     * Returns units {@code held -> available}. Guarded by {@code held >= quantity} so a double
     * release is rejected by the statement instead of creating tickets out of nothing.
     */
    @Modifying
    @Query(value = """
            update event_inventory
               set available = available + :quantity,
                   held = held - :quantity
             where event_id = :eventId
               and ticket_type_id = :ticketTypeId
               and held >= :quantity
            """, nativeQuery = true)
    int release(
            @Param("eventId") UUID eventId,
            @Param("ticketTypeId") UUID ticketTypeId,
            @Param("quantity") int quantity);

    /**
     * Turns held units into sold ones when the payment is authorized. The {@code held >= quantity}
     * guard makes a second confirmation of the same units impossible; the balance CHECK still has
     * to hold afterwards.
     */
    @Modifying
    @Query(value = """
            update event_inventory
               set held = held - :quantity,
                   sold = sold + :quantity
             where event_id = :eventId
               and ticket_type_id = :ticketTypeId
               and held >= :quantity
            """, nativeQuery = true)
    int confirm(
            @Param("eventId") UUID eventId,
            @Param("ticketTypeId") UUID ticketTypeId,
            @Param("quantity") int quantity);
}
