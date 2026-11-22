package com.eventflow.catalog.api;

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

import com.eventflow.catalog.api.dto.CreateEventRequest;
import com.eventflow.catalog.api.dto.EventDetailResponse;
import com.eventflow.catalog.api.dto.EventSummaryResponse;
import com.eventflow.catalog.api.dto.PageResponse;
import com.eventflow.catalog.application.CatalogService;
import com.eventflow.catalog.application.CreateEventCommand;
import com.eventflow.catalog.application.CreateTicketTypeCommand;
import com.eventflow.catalog.domain.Event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final CatalogService catalogService;

    @GetMapping
    public PageResponse<EventSummaryResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<Event> events = catalogService.listPublishedEvents(from, to, page, size);
        return CatalogMapper.toPage(events, CatalogMapper::toSummary);
    }

    @GetMapping("/{eventId}")
    public EventDetailResponse get(@PathVariable UUID eventId) {
        return CatalogMapper.toDetail(catalogService.getEvent(eventId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventDetailResponse create(@Valid @RequestBody CreateEventRequest request) {
        CreateEventCommand command = new CreateEventCommand(
                request.name(),
                request.venueId(),
                request.startsAt(),
                request.ticketTypes().stream()
                        .map(ticket -> new CreateTicketTypeCommand(ticket.name(), ticket.price(), ticket.capacity()))
                        .toList());
        return CatalogMapper.toDetail(catalogService.createEvent(command));
    }

    @PostMapping("/{eventId}/publish")
    public EventDetailResponse publish(@PathVariable UUID eventId) {
        return CatalogMapper.toDetail(catalogService.publishEvent(eventId));
    }
}
