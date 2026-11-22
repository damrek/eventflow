package com.eventflow.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Event aggregate. Owns its ticket types and the only allowed lifecycle transition:
 * {@code DRAFT -> PUBLISHED}.
 */
@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EventStatus status;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TicketType> ticketTypes = new ArrayList<>();

    public static Event draft(Venue venue, String name, Instant startsAt, List<TicketTypeSpec> specs) {
        if (venue == null) {
            throw new BusinessRuleException("EVENT_VENUE_REQUIRED", "An event requires a venue");
        }
        if (startsAt == null) {
            throw new BusinessRuleException("EVENT_START_REQUIRED", "An event requires a start date");
        }
        if (specs == null || specs.isEmpty()) {
            throw new BusinessRuleException("EVENT_WITHOUT_TICKET_TYPES", "An event requires at least one ticket type");
        }

        Event event = new Event();
        event.id = UUID.randomUUID();
        event.venue = venue;
        event.name = Checks.text("EVENT_NAME_INVALID", "Event name", name, 200);
        event.startsAt = startsAt;
        event.status = EventStatus.DRAFT;

        for (TicketTypeSpec spec : specs) {
            event.addTicketType(TicketType.create(event, spec.name(), spec.price(), spec.capacity()));
        }
        event.assertUniqueTicketNames();
        event.assertWithinVenueCapacity();
        return event;
    }

    /**
     * Publishes the event. Booking will use this transition to initialize inventory, so it
     * must be explicit and repeatable only through a 409 conflict.
     */
    public void publish(Instant now) {
        if (status == EventStatus.PUBLISHED) {
            throw new StateConflictException("EVENT_ALREADY_PUBLISHED", "Event " + id + " is already published");
        }
        if (ticketTypes.isEmpty()) {
            throw new BusinessRuleException("EVENT_WITHOUT_TICKET_TYPES", "Event " + id + " has no ticket types");
        }
        if (!startsAt.isAfter(now)) {
            throw new BusinessRuleException(
                    "EVENT_START_IN_PAST", "Event " + id + " starts at " + startsAt + " and cannot be published");
        }
        assertWithinVenueCapacity();
        this.status = EventStatus.PUBLISHED;
    }

    public int totalCapacity() {
        return ticketTypes.stream().mapToInt(TicketType::getInitialCapacity).sum();
    }

    /**
     * Kept explicit (Lombok does not generate it) so callers cannot mutate the aggregate's
     * collection; ticket types are created only through {@link #draft}.
     */
    public List<TicketType> getTicketTypes() {
        return Collections.unmodifiableList(ticketTypes);
    }

    private void addTicketType(TicketType ticketType) {
        ticketTypes.add(ticketType);
    }

    private void assertUniqueTicketNames() {
        Set<String> names = new HashSet<>();
        for (TicketType ticketType : ticketTypes) {
            if (!names.add(ticketType.getName())) {
                throw new BusinessRuleException(
                        "DUPLICATE_TICKET_TYPE_NAME", "Ticket type name is duplicated: " + ticketType.getName());
            }
        }
    }

    private void assertWithinVenueCapacity() {
        int total = totalCapacity();
        if (total > venue.getCapacity()) {
            throw new BusinessRuleException(
                    "EVENT_CAPACITY_EXCEEDED",
                    "Ticket capacity " + total + " exceeds venue capacity " + venue.getCapacity());
        }
    }
}
