package com.eventflow.booking.reservation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    /**
     * Returns 1 when this transaction owns the key, 0 when another request already inserted it.
     * A concurrent duplicate blocks here until the winner commits, so a 0 always finds the
     * winner's stored response instead of racing an unfinished one.
     */
    @Modifying
    @Query(value = """
            insert into idempotency_request (id, user_id, idempotency_key, request_hash, created_at)
            values (:id, :userId, :idempotencyKey, :requestHash, :createdAt)
            on conflict (user_id, idempotency_key) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("userId") String userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("createdAt") Instant createdAt);

    @Modifying
    @Query(value = """
            update idempotency_request
               set response_status = :status,
                   response_body = :body
             where user_id = :userId
               and idempotency_key = :idempotencyKey
            """, nativeQuery = true)
    int complete(
            @Param("userId") String userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("status") int status,
            @Param("body") String body);
}
