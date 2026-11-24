package com.eventflow.booking.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.booking.inventory.EventInventoryRepository;
import com.eventflow.booking.shared.messaging.EventPublisher;
import com.eventflow.booking.shared.metrics.BookingMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The Booking half of the payment saga. An authorized payment confirms the reservation and turns
 * the held units into sold ones; a rejection fails it and returns the units; an authorization
 * that arrives too late never sells anything and is answered with a refund compensation.
 *
 * <p>Every branch runs in the consumer transaction together with the inbox claim and the outbox
 * writes, so a crash repeats the whole effect and a duplicate delivery finds the inbox marker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationPaymentService {

    private final ReservationRepository reservations;
    private final EventInventoryRepository inventory;
    private final EventPublisher publisher;
    private final Clock clock;
    private final BookingMetrics metrics;

    @Transactional
    public void authorized(UUID reservationId, UUID paymentId) {
        Reservation reservation = require(reservationId);
        Instant now = clock.instant();
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Reservation {} is already confirmed; ignoring the duplicated authorization", reservationId);
            return;
        }
        if (reservation.getStatus() == ReservationStatus.PENDING_PAYMENT && !reservation.isDue(now)) {
            reservation.recordPayment(paymentId);
            reservation.confirm(now);
            ReservationInventory.confirmAll(inventory, reservation);
            metrics.confirmed();
            publisher.publish(
                    PaymentEventPayload.CONFIRMED,
                    PaymentEventPayload.CONFIRMED_ROUTING_KEY,
                    reservation.getId(),
                    PaymentEventPayload.of(reservation));
            log.info("Confirmed reservation {} with payment {}", reservationId, paymentId);
            return;
        }
        compensate(reservation, paymentId, now);
    }

    @Transactional
    public void rejected(UUID reservationId, UUID paymentId, String reason) {
        Reservation reservation = require(reservationId);
        if (reservation.getStatus() == ReservationStatus.PAYMENT_FAILED) {
            log.info("Reservation {} already failed its payment; ignoring the duplicate", reservationId);
            return;
        }
        if (reservation.getStatus() != ReservationStatus.PENDING_PAYMENT) {
            log.info("Ignoring rejection for reservation {} because it is {}", reservationId, reservation.getStatus());
            return;
        }
        reservation.recordPayment(paymentId);
        reservation.failPayment();
        ReservationInventory.releaseAll(inventory, reservation);
        publisher.publish(
                PaymentEventPayload.PAYMENT_FAILED,
                PaymentEventPayload.PAYMENT_FAILED_ROUTING_KEY,
                reservation.getId(),
                PaymentEventPayload.of(reservation));
        log.info("Payment for reservation {} was rejected: {}", reservationId, reason);
    }

    /**
     * The authorization cannot be honored: a reservation that expired before the answer (or was
     * cancelled or already failed) keeps its released inventory and gets its money back. The hold
     * of a reservation still in {@code HELD} is left to the expiry worker, because nothing about
     * it was sold.
     */
    private void compensate(Reservation reservation, UUID paymentId, Instant now) {
        if (reservation.getStatus() == ReservationStatus.PENDING_PAYMENT) {
            reservation.expire(now);
            ReservationInventory.releaseAll(inventory, reservation);
            publisher.publish(
                    ReservationEventPayload.EXPIRED,
                    ReservationEventPayload.EXPIRED_ROUTING_KEY,
                    reservation.getId(),
                    ReservationEventPayload.from(reservation));
        }
        reservation.recordPayment(paymentId);
        publisher.publish(
                RefundRequestPayload.EVENT_TYPE,
                RefundRequestPayload.ROUTING_KEY,
                reservation.getId(),
                new RefundRequestPayload(
                        paymentId,
                        reservation.getId(),
                        reservation.totalAmount(),
                        PaymentEventPayload.CURRENCY,
                        "Payment authorized while reservation " + reservation.getId()
                                + " was " + reservation.getStatus()));
        log.warn("Compensating payment {} for reservation {} which is {}",
                paymentId, reservation.getId(), reservation.getStatus());
    }

    private Reservation require(UUID reservationId) {
        return reservations
                .findDetailedById(reservationId)
                .orElseThrow(() -> new PermanentReservationException(
                        "Payment result for unknown reservation " + reservationId));
    }
}
