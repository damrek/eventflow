package com.eventflow.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.catalog.domain.BusinessRuleException;
import com.eventflow.catalog.domain.EntityNotFoundException;
import com.eventflow.catalog.domain.Event;
import com.eventflow.catalog.domain.EventRepository;
import com.eventflow.catalog.domain.EventStatus;
import com.eventflow.catalog.domain.TicketTypeSpec;
import com.eventflow.catalog.domain.Venue;
import com.eventflow.catalog.domain.VenueRepository;

@Service
public class CatalogService {

    /**
     * Upper bound used when the caller does not filter by end date. Chosen well inside the
     * PostgreSQL timestamp range instead of {@link Instant#MAX}.
     */
    private static final Instant OPEN_END = Instant.parse("9999-12-31T23:59:59Z");

    private final VenueRepository venues;
    private final EventRepository events;
    private final Clock clock;

    public CatalogService(VenueRepository venues, EventRepository events, Clock clock) {
        this.venues = venues;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Venue createVenue(CreateVenueCommand command) {
        return venues.save(Venue.create(command.name(), command.address(), command.capacity()));
    }

    @Transactional(readOnly = true)
    public List<Venue> listVenues() {
        return venues.findAll(Sort.by(Sort.Direction.ASC, "name"));
    }

    @Transactional
    public Event createEvent(CreateEventCommand command) {
        Venue venue = venues
                .findById(command.venueId())
                .orElseThrow(() -> EntityNotFoundException.of("VENUE_NOT_FOUND", "Venue", command.venueId()));

        List<TicketTypeSpec> specs = command.ticketTypes().stream()
                .map(ticket -> new TicketTypeSpec(ticket.name(), ticket.price(), ticket.capacity()))
                .toList();

        return events.save(Event.draft(venue, command.name(), command.startsAt(), specs));
    }

    @Transactional
    public Event publishEvent(UUID eventId) {
        Event event = events
                .findDetailedById(eventId)
                .orElseThrow(() -> EntityNotFoundException.of("EVENT_NOT_FOUND", "Event", eventId));
        event.publish(clock.instant());
        return event;
    }

    @Transactional(readOnly = true)
    public Event getEvent(UUID eventId) {
        return events
                .findDetailedById(eventId)
                .orElseThrow(() -> EntityNotFoundException.of("EVENT_NOT_FOUND", "Event", eventId));
    }

    /**
     * Public listing: only published events, ordered by start date. {@code from} is inclusive
     * and {@code to} is exclusive.
     */
    @Transactional(readOnly = true)
    public Page<Event> listPublishedEvents(Instant from, Instant to, int page, int size) {
        Instant lower = from == null ? Instant.EPOCH : from;
        Instant upper = to == null ? OPEN_END : to;
        if (!lower.isBefore(upper)) {
            throw new BusinessRuleException("INVALID_DATE_RANGE", "'from' must be before 'to'");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "startsAt"));
        return events.findForListing(EventStatus.PUBLISHED, lower, upper, pageable);
    }
}
