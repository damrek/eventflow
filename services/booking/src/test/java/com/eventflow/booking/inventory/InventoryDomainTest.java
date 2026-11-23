package com.eventflow.booking.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.eventflow.booking.shared.domain.BusinessRuleException;
import com.eventflow.booking.shared.domain.StateConflictException;

class InventoryDomainTest {

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();

    @Test
    void createsOneRowPerTicketTypeWithAllUnitsAvailable() {
        InventoryProvisioning provisioning = InventoryProvisioning.plan(
                eventId,
                List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), 400),
                        new InventoryItemSpec(vip, new BigDecimal("60.00"), 50)),
                List.of());

        assertThat(provisioning.created()).isTrue();
        assertThat(provisioning.rows()).hasSize(2);
        assertThat(provisioning.rows())
                .allSatisfy(row -> {
                    assertThat(row.getAvailable()).isEqualTo(row.getCapacity());
                    assertThat(row.getHeld()).isZero();
                    assertThat(row.getSold()).isZero();
                    assertThat(row.getEventId()).isEqualTo(eventId);
                });
    }

    @Test
    void acceptsAnIdenticalReplayWithoutCreatingRows() {
        InventoryProvisioning first = InventoryProvisioning.plan(
                eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), 400)), List.of());

        InventoryProvisioning replay = InventoryProvisioning.plan(
                eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), 400)), first.rows());

        assertThat(replay.created()).isFalse();
        assertThat(replay.rows()).hasSize(1);
    }

    @Test
    void refusesToChangeAnAlreadyProvisionedOffer() {
        InventoryProvisioning first = InventoryProvisioning.plan(
                eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), 400)), List.of());

        assertThatThrownBy(() -> InventoryProvisioning.plan(
                        eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), 500)), first.rows()))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("different offer");
    }

    @Test
    void rejectsEmptyOrDuplicateSpecs() {
        assertThatThrownBy(() -> InventoryProvisioning.plan(eventId, List.of(), List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least one ticket type");
        assertThatThrownBy(() -> InventoryProvisioning.plan(
                        eventId,
                        List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), 10),
                                new InventoryItemSpec(general, new BigDecimal("30.00"), 10)),
                        List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("only once");
    }

    @Test
    void rejectsNonPositiveCapacityAndNegativePrice() {
        assertThatThrownBy(() -> new InventoryItemSpec(general, new BigDecimal("25.00"), 0))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least 1");
        assertThatThrownBy(() -> new InventoryItemSpec(general, new BigDecimal("-1.00"), 10))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> new InventoryItemSpec(general, new BigDecimal("25.001"), 10))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at most 2 decimals");
    }
}
