package com.eventflow.booking.waitingroom;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes the waiting-room backlog as {@code waiting_room_users}. The value is read when
 * Prometheus scrapes, never on the reservation path, and {@link WaitingRoomService#queuedUsers()}
 * returns the last successful reading while Redis is down: a throttle must never turn a scrape
 * failure into an application failure.
 */
@Component
public class WaitingRoomUsersGauge {

    public WaitingRoomUsersGauge(MeterRegistry registry, WaitingRoomService waitingRoom) {
        Gauge.builder("waiting.room.users", waitingRoom, WaitingRoomService::queuedUsers)
                .description("Users queued in the waiting rooms of every event with a queue")
                .register(registry);
    }
}
