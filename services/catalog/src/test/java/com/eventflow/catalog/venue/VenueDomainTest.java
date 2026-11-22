package com.eventflow.catalog.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.eventflow.catalog.shared.domain.BusinessRuleException;

class VenueDomainTest {

    @Test
    void createsVenue() {
        Venue venue = Venue.create("  Arena Sur  ", " Calle 1 ", 5000);

        assertThat(venue.getId()).isNotNull();
        assertThat(venue.getName()).isEqualTo("Arena Sur");
        assertThat(venue.getAddress()).isEqualTo("Calle 1");
        assertThat(venue.getCapacity()).isEqualTo(5000);
    }

    @Test
    void rejectsCapacityBelowOne() {
        assertThatThrownBy(() -> Venue.create("Arena", "Calle 1", 0))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("VENUE_CAPACITY_INVALID"));
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> Venue.create("   ", "Calle 1", 10))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("VENUE_NAME_INVALID"));
    }
}
