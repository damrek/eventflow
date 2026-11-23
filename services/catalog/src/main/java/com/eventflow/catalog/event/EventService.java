package com.eventflow.catalog.event;

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

import com.eventflow.catalog.shared.domain.BusinessRuleException;
import com.eventflow.catalog.shared.domain.EntityNotFoundException;
import com.eventflow.catalog.shared.messaging.EventPublisher;
import com.eventflow.catalog.venue.Venue;
import com.eventflow.catalog.venue.VenueRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EventService {

    /**
     * Upper bound used when the caller does not filter by end date. Chosen well inside the
     * PostgreSQL timestamp range instead of {@link Instant#MAX}.
     */
    private static final Instant OPEN_END = Instant.parse("9999-12-31T23:59:59Z");

    private final EventRepository events;
    private final VenueRepository venues;
    private final Clock clock;
    private final EventPublisher publisher;

    @Transactional
    public Event create(String name, UUID venueId, Instant startsAt, List<TicketTypeSpec> ticketTypes) {
        Venue venue = venues
                .findById(venueId)
                .orElseThrow(() -> EntityNotFoundException.of("VENUE_NOT_FOUND", "Venue", venueId));
        return events.save(Event.draft(venue, name, startsAt, ticketTypes));
    }

    @Transactional
    public Event publish(UUID eventId) {
        Event event = events
                .findDetailedById(eventId)
                .orElseThrow(() -> EntityNotFoundException.of("EVENT_NOT_FOUND", "Event", eventId));
        Instant now = clock.instant();
        event.publish(now);
        publisher.publish(
                EventPublishedPayload.EVENT_TYPE,
                EventPublishedPayload.ROUTING_KEY,
                event.getId(),
                EventPublishedPayload.from(event));
        return event;
    }

    @Transactional(readOnly = true)
    public Event get(UUID eventId) {
        return events
                .findDetailedById(eventId)
                .orElseThrow(() -> EntityNotFoundException.of("EVENT_NOT_FOUND", "Event", eventId));
    }

    /**
     * Public listing: only published events, ordered by start date. {@code from} is inclusive
     * and {@code to} is exclusive.
     */
    @Transactional(readOnly = true)
    public Page<Event> listPublished(Instant from, Instant to, int page, int size) {
        Instant lower = from == null ? Instant.EPOCH : from;
        Instant upper = to == null ? OPEN_END : to;
        if (!lower.isBefore(upper)) {
            throw new BusinessRuleException("INVALID_DATE_RANGE", "'from' must be before 'to'");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "startsAt"));
        return events.findForListing(EventStatus.PUBLISHED, lower, upper, pageable);
    }
}
