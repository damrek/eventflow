package com.eventflow.booking.shared.web.rate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.eventflow.booking.config.BookingProperties;
import com.eventflow.booking.shared.domain.RateLimitedException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fixed-window limiter whose counter lives only in Redis, so every Booking replica shares it.
 * The increment and the TTL are one Lua script, which makes the first request of a window create
 * the key and set its expiry atomically. When Redis is unreachable the limiter fails open: the
 * rate limit is a protection, never a reason to reject a sale the database can serve.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FixedWindowRateLimiter {

    private static final String INCREMENT_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """;

    private static final DefaultRedisScript<Long> INCREMENT =
            new DefaultRedisScript<>(INCREMENT_SCRIPT, Long.class);

    private final StringRedisTemplate redis;
    private final BookingProperties properties;

    public void check(RateLimitEndpoint endpoint, String subject) {
        int max = limit(endpoint);
        String key = "rate:" + endpoint.key() + ":" + subject;
        Long count;
        try {
            count = redis.execute(INCREMENT, List.of(key),
                    Long.toString(properties.rateLimit().window().toMillis()));
        } catch (DataAccessException unavailable) {
            log.warn("Rate limit skipped because Redis is unavailable [endpoint={}]", endpoint.key(), unavailable);
            return;
        }
        if (count == null || count <= max) {
            return;
        }
        throw new RateLimitedException(endpoint.key(), retryAfterSeconds(key));
    }

    private int limit(RateLimitEndpoint endpoint) {
        return switch (endpoint) {
            case WAITING_ROOM_JOIN -> properties.rateLimit().joinMax();
            case RESERVATION_CREATE -> properties.rateLimit().reservationMax();
        };
    }

    private long retryAfterSeconds(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        if (ttl == null || ttl < 0) {
            return 1;
        }
        return Math.max(1, (ttl + 999) / 1000);
    }
}
