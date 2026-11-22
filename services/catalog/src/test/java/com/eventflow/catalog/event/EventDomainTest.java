package com.eventflow.catalog.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.eventflow.catalog.shared.domain.BusinessRuleException;
import com.eventflow.catalog.shared.domain.StateConflictException;
import com.eventflow.catalog.venue.Venue;

class EventDomainTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant FUTURE_START = Instant.parse("2026-07-01T19:00:00Z");

    private final Venue venue = Venue.create("Arena", "Main street 1", 100);

    @Test
    void createsDraftEventWithTicketTypes() {
        Event event = Event.draft(
                venue, "  Devoxx  ", FUTURE_START, List.of(spec("General", "25.00", 80), spec("VIP", "60.50", 20)));

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getName()).isEqualTo("Devoxx");
        assertThat(event.getTicketTypes()).hasSize(2);
        assertThat(event.totalCapacity()).isEqualTo(100);
        assertThat(event.getTicketTypes().getFirst().getPrice()).isEqualByComparingTo("25.00");
        assertThat(event.getTicketTypes().getFirst().getPrice().scale()).isEqualTo(2);
    }

    @Test
    void rejectsEventWithoutTicketTypes() {
        assertThatThrownBy(() -> Event.draft(venue, "Devoxx", FUTURE_START, List.of()))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("EVENT_WITHOUT_TICKET_TYPES"));
    }

    @Test
    void rejectsCapacityAboveVenueCapacity() {
        assertThatThrownBy(() -> Event.draft(
                        venue, "Devoxx", FUTURE_START, List.of(spec("General", "25.00", 80), spec("VIP", "60.00", 30))))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("EVENT_CAPACITY_EXCEEDED"));
    }

    @Test
    void rejectsDuplicatedTicketTypeNames() {
        assertThatThrownBy(() -> Event.draft(
                        venue, "Devoxx", FUTURE_START, List.of(spec("General", "25.00", 10), spec("General", "30.00", 10))))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("DUPLICATE_TICKET_TYPE_NAME"));
    }

    @Test
    void rejectsNegativeTicketPrice() {
        assertThatThrownBy(() -> Event.draft(venue, "Devoxx", FUTURE_START, List.of(spec("General", "-1.00", 10))))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("TICKET_PRICE_INVALID"));
    }

    @Test
    void rejectsPriceWithMoreThanTwoDecimals() {
        assertThatThrownBy(() -> Event.draft(venue, "Devoxx", FUTURE_START, List.of(spec("General", "10.999", 10))))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("TICKET_PRICE_SCALE_INVALID"));
    }

    @Test
    void rejectsTicketTypeWithoutCapacity() {
        assertThatThrownBy(() -> Event.draft(venue, "Devoxx", FUTURE_START, List.of(spec("General", "10.00", 0))))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("TICKET_CAPACITY_INVALID"));
    }

    @Test
    void publishesDraftEvent() {
        Event event = Event.draft(venue, "Devoxx", FUTURE_START, List.of(spec("General", "25.00", 10)));

        event.publish(NOW);

        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void rejectsPublishingTwice() {
        Event event = Event.draft(venue, "Devoxx", FUTURE_START, List.of(spec("General", "25.00", 10)));
        event.publish(NOW);

        assertThatThrownBy(() -> event.publish(NOW))
                .isInstanceOfSatisfying(StateConflictException.class, exception ->
                        assertThat(exception.code()).isEqualTo("EVENT_ALREADY_PUBLISHED"));
    }

    @Test
    void rejectsPublishingEventThatAlreadyStarted() {
        Event event = Event.draft(
                venue, "Devoxx", NOW.minusSeconds(60), List.of(spec("General", "25.00", 10)));

        assertThatThrownBy(() -> event.publish(NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("EVENT_START_IN_PAST"));
    }

    private static TicketTypeSpec spec(String name, String price, int capacity) {
        return new TicketTypeSpec(name, new BigDecimal(price), capacity);
    }
}
