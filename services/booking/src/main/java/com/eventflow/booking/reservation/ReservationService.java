package com.eventflow.booking.reservation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.booking.config.BookingProperties;
import com.eventflow.booking.inventory.EventInventory;
import com.eventflow.booking.inventory.EventInventoryRepository;
import com.eventflow.booking.reservation.dto.ReservationResponse;
import com.eventflow.booking.shared.domain.EntityNotFoundException;
import com.eventflow.booking.shared.domain.StateConflictException;
import com.eventflow.booking.shared.messaging.EventPublisher;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reservation use cases. Everything a creation does — the idempotency row, the conditional
 * inventory holds, the reservation and its items — happens in one transaction, so a failure
 * leaves neither held units nor a key without a response.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservations;
    private final EventInventoryRepository inventory;
    private final IdempotencyRepository idempotency;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final BookingProperties properties;
    private final EventPublisher publisher;

    @Transactional
    public ReservationResult create(
            String userId, IdempotencyKey key, UUID eventId, List<TicketSelection> requested) {
        List<TicketSelection> selections = TicketSelection.validated(requested);
        Instant now = clock.instant();
        String fingerprint = RequestFingerprint.of(eventId, selections);

        int claimed = idempotency.insertIfAbsent(
                UUID.randomUUID(), userId, key.value(), fingerprint, now);
        if (claimed == 0) {
            return replay(userId, key, fingerprint);
        }

        List<ReservationLine> lines = holdUnits(eventId, selections);
        Reservation reservation =
                reservations.save(Reservation.hold(userId, eventId, now, properties.reservation().ttl(), lines));
        ReservationResponse response = ReservationResponse.from(reservation);
        idempotency.complete(userId, key.value(), 201, jsonMapper.writeValueAsString(response));
        return new ReservationResult(201, response);
    }

    @Transactional(readOnly = true)
    public Reservation get(String userId, UUID reservationId) {
        return requireOwned(userId, reservationId);
    }

    @Transactional
    public Reservation cancel(String userId, UUID reservationId) {
        Reservation reservation = requireOwned(userId, reservationId);
        reservation.cancel(clock.instant());
        ReservationInventory.releaseAll(inventory, reservation);
        publisher.publish(
                ReservationEventPayload.CANCELLED,
                ReservationEventPayload.CANCELLED_ROUTING_KEY,
                reservation.getId(),
                ReservationEventPayload.from(reservation));
        return reservation;
    }

    /**
     * Starts the payment step. The state change and the request event commit together, so a
     * reservation is never {@code PENDING_PAYMENT} without a payment on its way.
     */
    @Transactional
    public Reservation checkout(String userId, UUID reservationId) {
        Reservation reservation = requireOwned(userId, reservationId);
        reservation.checkout(clock.instant());
        publisher.publish(
                PaymentEventPayload.REQUESTED,
                PaymentEventPayload.REQUESTED_ROUTING_KEY,
                reservation.getId(),
                PaymentEventPayload.of(reservation));
        return reservation;
    }

    /**
     * Holds one unit batch per ticket type, ordered by ticket type so two multi-item
     * reservations always take row locks in the same order and cannot deadlock. A zero-row
     * update means PostgreSQL saw no stock under the row lock; the whole transaction then rolls
     * back, releasing nothing because nothing was committed.
     */
    private List<ReservationLine> holdUnits(UUID eventId, List<TicketSelection> selections) {
        List<ReservationLine> lines = new ArrayList<>();
        List<TicketSelection> ordered =
                selections.stream().sorted(Comparator.comparing(TicketSelection::ticketTypeId)).toList();
        for (TicketSelection selection : ordered) {
            EventInventory row = inventory
                    .findByEventIdAndTicketTypeId(eventId, selection.ticketTypeId())
                    .orElseThrow(() -> EntityNotFoundException.of("INVENTORY_NOT_FOUND", "Inventory for event", eventId));
            if (inventory.hold(eventId, selection.ticketTypeId(), selection.quantity()) == 0) {
                throw new InsufficientTicketsException(selection.ticketTypeId(), selection.quantity());
            }
            lines.add(new ReservationLine(selection.ticketTypeId(), selection.quantity(), row.getPrice()));
        }
        return lines;
    }

    private ReservationResult replay(String userId, IdempotencyKey key, String fingerprint) {
        IdempotencyRecord record = idempotency
                .findByUserIdAndIdempotencyKey(userId, key.value())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key " + key.value() + " was claimed but its row is not visible"));
        if (!record.matches(fingerprint)) {
            throw new StateConflictException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key '" + key.value() + "' was already used with a different request");
        }
        if (!record.hasResponse()) {
            throw new IllegalStateException(
                    "Idempotency key " + key.value() + " has no recorded response");
        }
        return new ReservationResult(
                record.getResponseStatus(), jsonMapper.readValue(record.getResponseBody(), ReservationResponse.class));
    }

    private Reservation requireOwned(String userId, UUID reservationId) {
        Reservation reservation = reservations
                .findDetailedById(reservationId)
                .orElseThrow(() -> EntityNotFoundException.of("RESERVATION_NOT_FOUND", "Reservation", reservationId));
        if (!reservation.isOwnedBy(userId)) {
            throw EntityNotFoundException.of("RESERVATION_NOT_FOUND", "Reservation", reservationId);
        }
        return reservation;
    }
}
