package com.eventflow.booking.waitingroom;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Atomic operations of the waiting room. All three return a number so the result type is an
 * integer, which avoids depending on serializer semantics of multi-bulk replies.
 */
final class WaitingRoomScripts {

    /**
     * Inserts the user once with a server-assigned score and returns its zero-based rank. The
     * registry keeps the dispatcher from scanning the keyspace, and the queue TTL bounds a queue
     * whose members never come back.
     */
    static final DefaultRedisScript<Long> JOIN = new DefaultRedisScript<>("""
            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if rank == false then
              local seq = redis.call('INCR', KEYS[2])
              redis.call('PEXPIRE', KEYS[2], ARGV[3])
              redis.call('ZADD', KEYS[1], seq, ARGV[1])
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              redis.call('SADD', KEYS[3], ARGV[4])
              rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            end
            return rank
            """, Long.class);

    /**
     * Pops up to {@code count} members in order, writes one admission token per popped member and
     * deletes the queue (and its registry entry) when it drains. Two replicas racing here pop
     * disjoint members: every member is admitted at most once.
     */
    static final DefaultRedisScript<Long> ADMIT = new DefaultRedisScript<>("""
            local members = redis.call('ZRANGE', KEYS[1], 0, tonumber(ARGV[1]) - 1)
            for i, member in ipairs(members) do
              redis.call('SET', ARGV[3] .. member, ARGV[i + 4], 'PX', tonumber(ARGV[2]))
              redis.call('ZREM', KEYS[1], member)
            end
            if redis.call('ZCARD', KEYS[1]) == 0 then
              redis.call('DEL', KEYS[1])
              redis.call('SREM', KEYS[2], ARGV[4])
            end
            return #members
            """, Long.class);

    private WaitingRoomScripts() {
    }
}
