package com.eventflow.booking.reservation;

import java.math.BigDecimal;
import java.time.Duration;
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
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.eventflow.booking.shared.domain.BusinessRuleException;
import com.eventflow.booking.shared.domain.DomainChecks;
import com.eventflow.booking.shared.domain.StateConflictException;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Reservation aggregate. Owns its items and the state machine:
 * {@code HELD -> PENDING_PAYMENT -> CONFIRMED | PAYMENT_FAILED}, {@code HELD -> CANCELLED} and
 * {@code HELD | PENDING_PAYMENT -> EXPIRED}. Every transition is guarded here, so a late or
 * duplicated caller can never confirm, fail or release an expired reservation twice, and a
 * payment authorized after the deadline is compensated instead of selling released inventory.
 *
 * <p>{@code @Version} makes two concurrent state transitions of the same reservation conflict
 * instead of overwriting each other; the loser gets HTTP 409 and reloads.
 */
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Payment that settled this reservation; set when a payment result arrives. */
    @Column(name = "payment_id")
    private UUID paymentId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReservationItem> items = new ArrayList<>();

    public static Reservation hold(
            String userId, UUID eventId, Instant now, Duration ttl, List<ReservationLine> lines) {
        DomainChecks.notNull("RESERVATION_EVENT_REQUIRED", "Event id", eventId);
        DomainChecks.notNull("RESERVATION_CREATED_AT_REQUIRED", "Creation instant", now);
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new BusinessRuleException("RESERVATION_TTL_INVALID", "Hold duration must be positive");
        }
        if (lines == null || lines.isEmpty()) {
            throw new BusinessRuleException("RESERVATION_WITHOUT_ITEMS", "A reservation requires at least one item");
        }

        Reservation reservation = new Reservation();
        reservation.id = UUID.randomUUID();
        reservation.userId = DomainChecks.text("RESERVATION_USER_INVALID", "User id", userId, 100);
        reservation.eventId = eventId;
        reservation.status = ReservationStatus.HELD;
        reservation.createdAt = now;
        reservation.expiresAt = now.plus(ttl);

        Set<UUID> ticketTypes = new HashSet<>();
        for (ReservationLine line : lines) {
            if (!ticketTypes.add(line.ticketTypeId())) {
                throw new BusinessRuleException(
                        "RESERVATION_DUPLICATE_TICKET_TYPE", "Each ticket type may appear only once");
            }
            reservation.items.add(ReservationItem.create(reservation, line));
        }
        return reservation;
    }

    public void cancel(Instant now) {
        if (status != ReservationStatus.HELD) {
            throw new StateConflictException(
                    "RESERVATION_NOT_CANCELLABLE", "Reservation " + id + " is " + status + " and cannot be cancelled");
        }
        if (!expiresAt.isAfter(now)) {
            throw new StateConflictException(
                    "RESERVATION_EXPIRED", "Reservation " + id + " expired at " + expiresAt);
        }
        status = ReservationStatus.CANCELLED;
    }

    /** Starts the payment step. Only a live hold can be paid for; a second checkout is a conflict. */
    public void checkout(Instant now) {
        if (status != ReservationStatus.HELD) {
            throw new StateConflictException(
                    "RESERVATION_NOT_PAYABLE", "Reservation " + id + " is " + status + " and cannot start a payment");
        }
        if (!expiresAt.isAfter(now)) {
            throw new StateConflictException(
                    "RESERVATION_EXPIRED", "Reservation " + id + " expired at " + expiresAt);
        }
        status = ReservationStatus.PENDING_PAYMENT;
    }

    /** Payment authorized while the hold was still live: the units become sold. */
    public void confirm(Instant now) {
        if (status != ReservationStatus.PENDING_PAYMENT) {
            throw new StateConflictException(
                    "RESERVATION_NOT_CONFIRMABLE", "Reservation " + id + " is " + status + " and cannot be confirmed");
        }
        if (!expiresAt.isAfter(now)) {
            throw new StateConflictException(
                    "RESERVATION_EXPIRED", "Reservation " + id + " expired at " + expiresAt);
        }
        status = ReservationStatus.CONFIRMED;
    }

    /** Payment rejected while the hold was still live: the units go back to availability. */
    public void failPayment() {
        if (status != ReservationStatus.PENDING_PAYMENT) {
            throw new StateConflictException(
                    "RESERVATION_NOT_FAILABLE", "Reservation " + id + " is " + status + " and has no payment to fail");
        }
        status = ReservationStatus.PAYMENT_FAILED;
    }

    /** Records the payment that produced the result; a different payment is a bug, not a retry. */
    public void recordPayment(UUID paymentId) {
        DomainChecks.notNull("RESERVATION_PAYMENT_REQUIRED", "Payment id", paymentId);
        if (this.paymentId != null && !this.paymentId.equals(paymentId)) {
            throw new IllegalStateException(
                    "Reservation " + id + " is already linked to payment " + this.paymentId + ", not " + paymentId);
        }
        this.paymentId = paymentId;
    }

    public boolean isDue(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * Only the expiry worker or a payment-result listener calls this, for a reservation whose
     * deadline passed. The deadline is re-checked here so a bad claim cannot release units that
     * are still legitimately held.
     */
    public void expire(Instant now) {
        if (status != ReservationStatus.HELD && status != ReservationStatus.PENDING_PAYMENT) {
            throw new StateConflictException(
                    "RESERVATION_NOT_EXPIRY_ELIGIBLE", "Reservation " + id + " is " + status + " and cannot expire");
        }
        if (expiresAt.isAfter(now)) {
            throw new StateConflictException(
                    "RESERVATION_NOT_DUE", "Reservation " + id + " expires at " + expiresAt + ", not yet due");
        }
        status = ReservationStatus.EXPIRED;
    }

    public boolean isOwnedBy(String candidateUserId) {
        return userId.equals(candidateUserId);
    }

    public BigDecimal totalAmount() {
        return items.stream().map(ReservationItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Unmodifiable so a caller cannot mutate the aggregate's collection; items are created only
     * through {@link #hold}.
     */
    public List<ReservationItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
