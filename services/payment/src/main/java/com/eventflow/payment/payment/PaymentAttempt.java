package com.eventflow.payment.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Audit row of one provider call: which answer, in which order and how long it took. A call that
 * resulted in a timeout or an open circuit writes no row because the transaction rolls back, so
 * the table never records an authorization that did not happen.
 */
@Entity
@Table(
        name = "payment_attempt",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_attempt_number",
                columnNames = {"payment_id", "attempt_number"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private PaymentAttemptOutcome outcome;

    @Column(name = "provider_reference", length = 120)
    private String providerReference;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "error", length = 200)
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static PaymentAttempt record(
            Payment payment,
            int attemptNumber,
            PaymentAttemptOutcome outcome,
            String providerReference,
            String error,
            long latencyMs,
            Instant now) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = UUID.randomUUID();
        attempt.payment = payment;
        attempt.attemptNumber = attemptNumber;
        attempt.outcome = outcome;
        attempt.providerReference = providerReference;
        attempt.error = error;
        attempt.latencyMs = latencyMs;
        attempt.createdAt = now;
        return attempt;
    }
}
