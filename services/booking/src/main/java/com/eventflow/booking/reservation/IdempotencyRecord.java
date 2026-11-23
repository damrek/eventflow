package com.eventflow.booking.reservation;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Read model of a recorded idempotent request. Rows are written with native statements
 * (insert-or-ignore, then complete) so the unique {@code (user_id, idempotency_key)} constraint,
 * not application code, arbitrates concurrent duplicates.
 */
@Entity
@Table(
        name = "idempotency_request",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_idempotency_request_user_key",
                        columnNames = {"user_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public boolean matches(String candidateHash) {
        return requestHash.equals(candidateHash);
    }

    public boolean hasResponse() {
        return responseStatus != null && responseBody != null;
    }
}
