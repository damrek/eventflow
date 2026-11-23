package com.eventflow.booking.waitingroom;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eventflow.booking.config.BookingProperties;
import com.eventflow.booking.inventory.InventoryService;
import com.eventflow.booking.shared.domain.EntityNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admits queued users in batches while the event still has stock. It iterates the registry of
 * events with a queue instead of scanning the keyspace, and it reads availability from
 * PostgreSQL, the only authority: a sold-out event admits nobody until units return.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WaitingRoomDispatcher {

    private final StringRedisTemplate redis;
    private final WaitingRoomService waitingRoom;
    private final InventoryService inventory;
    private final BookingProperties properties;

    @Scheduled(fixedDelayString = "${booking.waiting-room.dispatch-interval}")
    public void tick() {
        dispatch();
    }

    /**
     * One pass over the registered queues. Returns the admitted members per event so tests can
     * drive admission deterministically instead of waiting for the scheduler.
     */
    public Map<UUID, Integer> dispatch() {
        Map<UUID, Integer> admitted = new LinkedHashMap<>();
        Set<String> eventIds;
        try {
            eventIds = redis.opsForSet().members(WaitingRoomKeys.REGISTRY);
        } catch (DataAccessException unavailable) {
            log.warn("Waiting-room dispatch skipped because Redis is unavailable", unavailable);
            return admitted;
        }
        if (eventIds == null || eventIds.isEmpty()) {
            return admitted;
        }
        for (String member : eventIds) {
            UUID eventId = WaitingRoomKeys.parseEventId(member);
            if (eventId == null) {
                forget(member);
                continue;
            }
            try {
                if (!Boolean.TRUE.equals(redis.hasKey(WaitingRoomKeys.queue(eventId)))) {
                    forget(member);
                    continue;
                }
                if (!hasAvailability(eventId)) {
                    continue;
                }
                int count = waitingRoom.admitBatch(eventId, properties.waitingRoom().batchSize());
                if (count > 0) {
                    admitted.put(eventId, count);
                    log.info("Waiting room admitted {} user(s) for event {}", count, eventId);
                }
            } catch (DataAccessException unavailable) {
                log.warn("Waiting-room dispatch stopped because Redis is unavailable", unavailable);
                return admitted;
            }
        }
        return admitted;
    }

    private boolean hasAvailability(UUID eventId) {
        try {
            return inventory.availability(eventId).stream().anyMatch(row -> row.getAvailable() > 0);
        } catch (EntityNotFoundException notProvisionedYet) {
            return false;
        }
    }

    private void forget(String member) {
        redis.opsForSet().remove(WaitingRoomKeys.REGISTRY, member);
    }
}
