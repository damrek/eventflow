package com.eventflow.payment.payment;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    @Query("select coalesce(max(attempt.attemptNumber), 0) from PaymentAttempt attempt where attempt.payment.id = :paymentId")
    int lastAttemptNumber(@Param("paymentId") UUID paymentId);

    @Query("select count(attempt) from PaymentAttempt attempt where attempt.payment.id = :paymentId")
    long countForPayment(@Param("paymentId") UUID paymentId);
}
