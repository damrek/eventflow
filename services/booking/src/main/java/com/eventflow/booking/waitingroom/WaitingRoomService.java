package com.eventflow.booking.waitingroom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.eventflow.booking.config.BookingProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The virtual waiting room of an event. A user joins once and keeps their server-assigned
 * position; the dispatcher admits batches and writes a short-lived token that allows a
 * reservation while the queue is active. Redis is a throttle, never the authority on stock: when
 * it is unreachable join/status answer 503 and the admission gate fails open (ADR 0011).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WaitingRoomService {

    /** Bounds a queue whose members never return; positions are still server-assigned. */
    private static final Duration QUEUE_TTL = Duration.ofHours(24);
    private static final Duration SEQUENCE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final BookingProperties properties;
    private final Clock clock;

    public WaitingRoomStatusResponse join(UUID eventId, String userId) {
        try {
            Admission admission = admission(eventId, userId);
            if (admission != null) {
                return WaitingRoomStatusResponse.admitted(eventId, userId, admission.token(), admission.expiresAt());
            }
            Long rank = redis.execute(
                    WaitingRoomScripts.JOIN,
                    List.of(
                            WaitingRoomKeys.queue(eventId),
                            WaitingRoomKeys.sequence(eventId),
                            WaitingRoomKeys.REGISTRY),
                    userId,
                    Long.toString(QUEUE_TTL.toMillis()),
                    Long.toString(SEQUENCE_TTL.toMillis()),
                    eventId.toString());
            // A dispatcher tick may have admitted this user between the token read and the
            // insert; reporting ADMITTED keeps the client from waiting for nothing.
            Admission raced = admission(eventId, userId);
            if (raced != null) {
                return WaitingRoomStatusResponse.admitted(eventId, userId, raced.token(), raced.expiresAt());
            }
            long position = (rank == null ? 0 : rank) + 1;
            return WaitingRoomStatusResponse.waiting(eventId, userId, position, waitingCount(eventId));
        } catch (DataAccessException unavailable) {
            log.warn("Waiting room join failed because Redis is unavailable [eventId={}]", eventId, unavailable);
            throw new WaitingRoomUnavailableException(unavailable);
        }
    }

    public WaitingRoomStatusResponse status(UUID eventId, String userId) {
        try {
            Admission admission = admission(eventId, userId);
            if (admission != null) {
                return WaitingRoomStatusResponse.admitted(eventId, userId, admission.token(), admission.expiresAt());
            }
            Long rank = redis.opsForZSet().rank(WaitingRoomKeys.queue(eventId), userId);
            long waitingCount = waitingCount(eventId);
            if (rank == null) {
                return WaitingRoomStatusResponse.notJoined(eventId, userId, waitingCount);
            }
            return WaitingRoomStatusResponse.waiting(eventId, userId, rank + 1, waitingCount);
        } catch (DataAccessException unavailable) {
            log.warn("Waiting room status failed because Redis is unavailable [eventId={}]", eventId, unavailable);
            throw new WaitingRoomUnavailableException(unavailable);
        }
    }

    /**
     * Admission gate for reservation creation. It only applies while the event's queue exists.
     * A missing, expired or foreign token is rejected; a Redis failure lets the request through,
     * because inventory correctness is decided by PostgreSQL and a dead Redis must not stop sales.
     */
    public void requireAdmission(UUID eventId, String userId, String token) {
        Boolean active;
        try {
            active = redis.hasKey(WaitingRoomKeys.queue(eventId));
        } catch (DataAccessException unavailable) {
            log.warn("Admission gate skipped because Redis is unavailable [eventId={}]", eventId, unavailable);
            return;
        }
        if (!Boolean.TRUE.equals(active)) {
            return;
        }
        String expected;
        try {
            expected = redis.opsForValue().get(WaitingRoomKeys.token(eventId, userId));
        } catch (DataAccessException unavailable) {
            log.warn("Admission gate skipped because Redis is unavailable [eventId={}]", eventId, unavailable);
            return;
        }
        if (token == null || token.isBlank() || !token.equals(expected)) {
            throw new NotAdmittedException(
                    "The waiting room is active for this event; join it and use the admission token");
        }
    }

    /**
     * Admits up to {@code batchSize} queued users and returns how many were admitted. Tokens are
     * generated before the script so the pop and the token writes stay atomic; a replica that
     * loses the race simply pops fewer members.
     */
    public int admitBatch(UUID eventId, int batchSize) {
        Long queued = redis.opsForZSet().zCard(WaitingRoomKeys.queue(eventId));
        if (queued == null || queued == 0) {
            return 0;
        }
        int count = (int) Math.min(queued, batchSize);
        List<String> arguments = new ArrayList<>(4 + count);
        arguments.add(Integer.toString(count));
        arguments.add(Long.toString(properties.waitingRoom().admissionTtl().toMillis()));
        arguments.add(WaitingRoomKeys.tokenPrefix(eventId));
        arguments.add(eventId.toString());
        for (int i = 0; i < count; i++) {
            arguments.add(UUID.randomUUID().toString());
        }
        Long admitted = redis.execute(
                WaitingRoomScripts.ADMIT,
                List.of(WaitingRoomKeys.queue(eventId), WaitingRoomKeys.REGISTRY),
                arguments.toArray());
        return admitted == null ? 0 : admitted.intValue();
    }

    private record Admission(String token, Instant expiresAt) {
    }

    private Admission admission(UUID eventId, String userId) {
        String token = redis.opsForValue().get(WaitingRoomKeys.token(eventId, userId));
        if (token == null) {
            return null;
        }
        Long ttl = redis.getExpire(WaitingRoomKeys.token(eventId, userId), TimeUnit.MILLISECONDS);
        Instant expiresAt = ttl == null || ttl < 0 ? null : clock.instant().plusMillis(ttl);
        return new Admission(token, expiresAt);
    }

    private long waitingCount(UUID eventId) {
        Long count = redis.opsForZSet().zCard(WaitingRoomKeys.queue(eventId));
        return count == null ? 0 : count;
    }
}
