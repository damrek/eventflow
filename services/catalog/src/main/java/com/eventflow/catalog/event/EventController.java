package com.eventflow.catalog.event;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.eventflow.catalog.event.dto.CreateEventRequest;
import com.eventflow.catalog.event.dto.EventDetailResponse;
import com.eventflow.catalog.event.dto.EventSummaryResponse;
import com.eventflow.catalog.shared.web.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public PageResponse<EventSummaryResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<Event> events = eventService.listPublished(from, to, page, size);
        return PageResponse.of(events, EventSummaryResponse::from);
    }

    @GetMapping("/{eventId}")
    public EventDetailResponse get(@PathVariable UUID eventId) {
        return EventDetailResponse.from(eventService.get(eventId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventDetailResponse create(@Valid @RequestBody CreateEventRequest request) {
        return EventDetailResponse.from(eventService.create(
                request.name(),
                request.venueId(),
                request.startsAt(),
                request.ticketTypes().stream()
                        .map(ticket -> new TicketTypeSpec(ticket.name(), ticket.price(), ticket.capacity()))
                        .toList()));
    }

    @PostMapping("/{eventId}/publish")
    public EventDetailResponse publish(@PathVariable UUID eventId) {
        return EventDetailResponse.from(eventService.publish(eventId));
    }
}
