package com.eventflow.booking.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.booking.config.BookingProperties;
import com.eventflow.booking.inventory.EventInventoryRepository;
import com.eventflow.booking.shared.messaging.EventPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Releases units from reservations whose deadline passed. The claim is a single statement with
 * {@code FOR UPDATE SKIP LOCKED}, and the state change plus the inventory release run in the same
 * transaction, so two replicas can scan concurrently without expiring or releasing twice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationExpiryWorker {

    private final ReservationRepository reservations;
    private final EventInventoryRepository inventory;
    private final Clock clock;
    private final BookingProperties properties;
    private final EventPublisher publisher;

    @Scheduled(fixedDelayString = "${booking.expiry.interval}")
    @Transactional
    public int expireDue() {
        Instant now = clock.instant();
        List<UUID> due = reservations.claimDue(now, properties.expiry().batchSize());
        for (UUID reservationId : due) {
            Reservation reservation = reservations
                    .findDetailedById(reservationId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Expiry claimed reservation " + reservationId + " which is no longer readable"));
            reservation.expire(now);
            ReservationInventory.releaseAll(inventory, reservation);
            publisher.publish(
                    ReservationEventPayload.EXPIRED,
                    ReservationEventPayload.EXPIRED_ROUTING_KEY,
                    reservation.getId(),
                    ReservationEventPayload.from(reservation));
            log.info("Expired reservation {}", reservationId);
        }
        return due.size();
    }
}
