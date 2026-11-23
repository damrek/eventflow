package com.eventflow.payment.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One logical payment per reservation. The state machine and its guards live here: only a
 * {@code PENDING} payment can be authorized or rejected, only an {@code AUTHORIZED} one can be
 * refunded, and a settled payment never changes again, so a duplicated provider result or a
 * duplicated refund request cannot move money twice.
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    private UUID id;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private UUID reservationId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "provider_reference", length = 120)
    private String providerReference;

    @Column(name = "rejection_reason", length = 200)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static Payment open(
            UUID reservationId, UUID eventId, String userId, BigDecimal amount, String currency, Instant now) {
        if (reservationId == null || eventId == null) {
            throw new PermanentPaymentException("A payment needs its reservation and event");
        }
        if (userId == null || userId.isBlank()) {
            throw new PermanentPaymentException("A payment needs its user");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new PermanentPaymentException("A payment amount must be positive");
        }
        if (currency == null || currency.length() != 3) {
            throw new PermanentPaymentException("A payment needs a three-letter currency");
        }

        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.reservationId = reservationId;
        payment.eventId = eventId;
        payment.userId = userId;
        payment.amount = amount;
        payment.currency = currency;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    public void authorize(String providerReference, Instant now) {
        requireStatus(PaymentStatus.PENDING);
        if (providerReference == null || providerReference.isBlank()) {
            throw new PermanentPaymentException("An authorization needs its provider reference");
        }
        status = PaymentStatus.AUTHORIZED;
        this.providerReference = providerReference;
        updatedAt = now;
    }

    public void reject(String reason, Instant now) {
        requireStatus(PaymentStatus.PENDING);
        status = PaymentStatus.REJECTED;
        rejectionReason = truncate(reason);
        updatedAt = now;
    }

    /** Compensation of a late authorization. Simulated, so no provider call is involved. */
    public void refund(Instant now) {
        requireStatus(PaymentStatus.AUTHORIZED);
        status = PaymentStatus.REFUNDED;
        updatedAt = now;
    }

    public boolean isSettled() {
        return status != PaymentStatus.PENDING;
    }

    private void requireStatus(PaymentStatus expected) {
        if (status != expected) {
            throw new PermanentPaymentException(
                    "Payment " + id + " is " + status + " and cannot become " + expected);
        }
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Provider rejected the authorization";
        }
        return reason.length() <= 200 ? reason : reason.substring(0, 200);
    }
}
