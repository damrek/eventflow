package com.eventflow.booking.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.eventflow.booking.shared.domain.BusinessRuleException;
import com.eventflow.booking.shared.domain.StateConflictException;

class ReservationDomainTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();

    @Test
    void holdsUnitsUntilTheDeadline() {
        Reservation reservation =
                hold(List.of(line(general, 2, "25.00"), line(vip, 1, "60.00")));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(reservation.getCreatedAt()).isEqualTo(NOW);
        assertThat(reservation.totalAmount()).isEqualByComparingTo("110.00");
        assertThat(reservation.getItems()).hasSize(2);
    }

    @Test
    void rejectsReservationWithoutItems() {
        assertThatThrownBy(() -> hold(List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least one item");
    }

    @Test
    void rejectsDuplicateTicketTypes() {
        assertThatThrownBy(() -> hold(List.of(line(general, 1, "25.00"), line(general, 2, "25.00"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("only once");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> new ReservationLine(general, 0, new BigDecimal("25.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void rejectsNegativeUnitPrice() {
        assertThatThrownBy(() -> new ReservationLine(general, 1, new BigDecimal("-1.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void rejectsNonPositiveHoldDuration() {
        assertThatThrownBy(() -> Reservation.hold("user", eventId, NOW, Duration.ZERO, List.of(line(general, 1, "25.00"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void cancelsHeldReservationAndDoesNotAllowASecondCancel() {
        Reservation reservation = hold(List.of(line(general, 2, "25.00")));

        reservation.cancel(NOW.plusSeconds(60));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThatThrownBy(() -> reservation.cancel(NOW.plusSeconds(120)))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    @Test
    void refusesToCancelAReservationPastItsDeadline() {
        Reservation reservation = hold(List.of(line(general, 1, "25.00")));

        assertThatThrownBy(() -> reservation.cancel(NOW.plus(TTL)))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("expired at");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void expiresOnlyDueHeldReservations() {
        Reservation reservation = hold(List.of(line(general, 1, "25.00")));

        assertThatThrownBy(() -> reservation.expire(NOW.plusSeconds(60)))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("not yet due");

        reservation.expire(NOW.plus(TTL));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThatThrownBy(() -> reservation.expire(NOW.plus(TTL)))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("cannot expire");
    }

    @Test
    void checkoutMovesAHoldToPendingPaymentOnlyOnce() {
        Reservation reservation = hold(List.of(line(general, 1, "25.00")));

        reservation.checkout(NOW.plusSeconds(60));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);

        assertThatThrownBy(() -> reservation.checkout(NOW.plusSeconds(120)))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("cannot start a payment");
    }

    @Test
    void refusesToCheckoutPastTheDeadline() {
        Reservation reservation = hold(List.of(line(general, 1, "25.00")));

        assertThatThrownBy(() -> reservation.checkout(NOW.plus(TTL)))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("expired at");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void confirmsOnlyALivePendingPayment() {
        Reservation reservation = hold(List.of(line(general, 1, "25.00")));
        reservation.checkout(NOW.plusSeconds(30));

        reservation.confirm(NOW.plusSeconds(60));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThatThrownBy(() -> reservation.confirm(NOW.plusSeconds(90)))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("cannot be confirmed");
    }

    @Test
    void refusesToConfirmAPaymentWhoseDeadlinePassed() {
        Reservation reservation = hold(List.of(line(general, 1, "25.00")));
        reservation.checkout(NOW.plusSeconds(30));

        assertThatThrownBy(() -> reservation.confirm(NOW.plus(TTL)))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("expired at");
        assertThat(reservation.isDue(NOW.plus(TTL))).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
    }

    @Test
    void failsOnlyAPendingPaymentAndRecordsItsPayment() {
        Reservation reservation = hold(List.of(line(general, 1, "25.00")));
        UUID paymentId = UUID.randomUUID();
        reservation.checkout(NOW.plusSeconds(30));

        reservation.recordPayment(paymentId);
        reservation.failPayment();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_FAILED);
        assertThatThrownBy(() -> reservation.failPayment())
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("no payment to fail");
        assertThatThrownBy(() -> reservation.recordPayment(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already linked");
    }

    @Test
    void expiresAPendingPaymentReservation() {
        Reservation reservation = hold(List.of(line(general, 1, "25.00")));
        reservation.checkout(NOW.plusSeconds(30));

        reservation.expire(NOW.plus(TTL));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void ownershipIsExact() {
        Reservation reservation = hold(List.of(line(general, 1, "25.00")));

        assertThat(reservation.isOwnedBy("user-a")).isTrue();
        assertThat(reservation.isOwnedBy("user-b")).isFalse();
    }

    @Test
    void ticketSelectionsRejectDuplicatesAndEmptyLists() {
        assertThatThrownBy(() -> TicketSelection.validated(List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least one item");
        assertThatThrownBy(() -> TicketSelection.validated(
                        List.of(new TicketSelection(general, 1), new TicketSelection(general, 1))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("only once");
    }

    @Test
    void idempotencyKeyIsRequiredAndBounded() {
        assertThatThrownBy(() -> new IdempotencyKey("  "))
                .isInstanceOf(com.eventflow.booking.shared.domain.InvalidRequestException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> new IdempotencyKey("k".repeat(201)))
                .isInstanceOf(com.eventflow.booking.shared.domain.InvalidRequestException.class)
                .hasMessageContaining("at most 200");
        assertThat(new IdempotencyKey("  order-1  ").value()).isEqualTo("order-1");
    }

    @Test
    void fingerprintDependsOnTheSemanticRequestOnly() {
        String first = RequestFingerprint.of(eventId, List.of(
                new TicketSelection(vip, 1), new TicketSelection(general, 2)));
        String reordered = RequestFingerprint.of(eventId, List.of(
                new TicketSelection(general, 2), new TicketSelection(vip, 1)));
        String differentQuantity = RequestFingerprint.of(eventId, List.of(
                new TicketSelection(general, 3), new TicketSelection(vip, 1)));

        assertThat(first).isEqualTo(reordered).hasSize(64);
        assertThat(differentQuantity).isNotEqualTo(first);
    }

    private Reservation hold(List<ReservationLine> lines) {
        return Reservation.hold("user-a", eventId, NOW, TTL, lines);
    }

    private ReservationLine line(UUID ticketTypeId, int quantity, String price) {
        return new ReservationLine(ticketTypeId, quantity, new BigDecimal(price));
    }
}
