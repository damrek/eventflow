package com.eventflow.catalog.api;

import java.util.function.Function;

import org.springframework.data.domain.Page;

import com.eventflow.catalog.api.dto.EventDetailResponse;
import com.eventflow.catalog.api.dto.EventSummaryResponse;
import com.eventflow.catalog.api.dto.PageResponse;
import com.eventflow.catalog.api.dto.TicketTypeResponse;
import com.eventflow.catalog.api.dto.VenueResponse;
import com.eventflow.catalog.domain.Event;
import com.eventflow.catalog.domain.TicketType;
import com.eventflow.catalog.domain.Venue;

/**
 * Explicit mapping between the domain and the HTTP contract. Kept manual on purpose: the
 * response shape is a published contract and should not silently follow entity changes.
 */
public final class CatalogMapper {

    private CatalogMapper() {
    }

    public static VenueResponse toResponse(Venue venue) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getAddress(), venue.getCapacity());
    }

    public static EventSummaryResponse toSummary(Event event) {
        return new EventSummaryResponse(
                event.getId(),
                event.getName(),
                event.getVenue().getId(),
                event.getVenue().getName(),
                event.getStartsAt(),
                event.getStatus());
    }

    public static EventDetailResponse toDetail(Event event) {
        return new EventDetailResponse(
                event.getId(),
                event.getName(),
                event.getVenue().getId(),
                event.getVenue().getName(),
                event.getStartsAt(),
                event.getStatus(),
                event.totalCapacity(),
                event.getTicketTypes().stream().map(CatalogMapper::toResponse).toList());
    }

    private static TicketTypeResponse toResponse(TicketType ticketType) {
        return new TicketTypeResponse(
                ticketType.getId(), ticketType.getName(), ticketType.getPrice(), ticketType.getInitialCapacity());
    }

    public static <S, T> PageResponse<T> toPage(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
