package com.eventflow.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The payment state machine is the only guard against double settlement, so its transitions are
 * pinned without a database or a broker.
 */
class PaymentDomainTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void opensPendingAndAuthorizes() {
        Payment payment = open();

        payment.authorize("SIM-1", NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getProviderReference()).isEqualTo("SIM-1");
        assertThat(payment.isSettled()).isTrue();
    }

    @Test
    void rejectsWithItsReason() {
        Payment payment = open();

        payment.reject("insufficient funds", NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(payment.getRejectionReason()).isEqualTo("insufficient funds");
    }

    @Test
    void refundsOnlyAnAuthorizedPayment() {
        Payment payment = open();
        payment.authorize("SIM-1", NOW);

        payment.refund(NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThatThrownBy(() -> payment.refund(NOW)).isInstanceOf(PermanentPaymentException.class);
    }

    @Test
    void refusesToSettleTwiceOrRefundARejectedPayment() {
        Payment authorized = open();
        authorized.authorize("SIM-1", NOW);
        assertThatThrownBy(() -> authorized.authorize("SIM-2", NOW))
                .isInstanceOf(PermanentPaymentException.class);
        assertThatThrownBy(() -> authorized.reject("late", NOW))
                .isInstanceOf(PermanentPaymentException.class);

        Payment rejected = open();
        rejected.reject("insufficient funds", NOW);
        assertThatThrownBy(() -> rejected.refund(NOW)).isInstanceOf(PermanentPaymentException.class);
    }

    @Test
    void refusesInvalidPaymentData() {
        assertThatThrownBy(() -> Payment.open(UUID.randomUUID(), UUID.randomUUID(), "user-1",
                        new BigDecimal("0.00"), "EUR", NOW))
                .isInstanceOf(PermanentPaymentException.class);
        assertThatThrownBy(() -> Payment.open(UUID.randomUUID(), UUID.randomUUID(), "user-1",
                        new BigDecimal("10.00"), "EURO", NOW))
                .isInstanceOf(PermanentPaymentException.class);
        assertThatThrownBy(() -> Payment.open(UUID.randomUUID(), UUID.randomUUID(), " ",
                        new BigDecimal("10.00"), "EUR", NOW))
                .isInstanceOf(PermanentPaymentException.class);
    }

    private static Payment open() {
        return Payment.open(
                UUID.randomUUID(), UUID.randomUUID(), "user-1", new BigDecimal("110.00"), "EUR", NOW);
    }
}
