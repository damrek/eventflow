package com.eventflow.payment.payment;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eventflow.payment.provider.PaymentGateway;
import com.eventflow.payment.provider.ProviderAuthorizationRequest;
import com.eventflow.payment.provider.ProviderAuthorizationResponse;
import com.eventflow.payment.shared.messaging.EventPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Payment use cases. The provider call, the attempt row, the payment transition and the result
 * event share one transaction: a transient provider failure rolls all of them back (including the
 * listener's inbox claim), so the broker redelivers instead of leaving a half-written payment.
 *
 * <p>A settled payment is never authorized twice: a second {@code PaymentRequested} for the same
 * reservation is a no-op, which is what keeps a duplicate delivery cheap even when the envelope
 * id differs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentGateway gateway;
    private final EventPublisher publisher;
    private final Clock clock;

    @Transactional
    public void requestPayment(PaymentRequestedPayload request) {
        validate(request);
        Payment payment = payments
                .findByReservationId(request.reservationId())
                .orElseGet(() -> payments.save(Payment.open(
                        request.reservationId(),
                        request.eventId(),
                        request.userId(),
                        request.amount(),
                        request.currency(),
                        clock.instant())));
        if (payment.isSettled()) {
            log.info("Payment {} for reservation {} is already {}; ignoring the request",
                    payment.getId(), payment.getReservationId(), payment.getStatus());
            return;
        }

        long started = System.nanoTime();
        ProviderAuthorizationResponse response = gateway.authorize(new ProviderAuthorizationRequest(
                payment.getId(), payment.getReservationId(), payment.getAmount(), payment.getCurrency()));
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        Instant now = clock.instant();
        int attemptNumber = attempts.lastAttemptNumber(payment.getId()) + 1;

        if (response.approved()) {
            payment.authorize(response.providerReference(), now);
            attempts.save(PaymentAttempt.record(
                    payment,
                    attemptNumber,
                    PaymentAttemptOutcome.AUTHORIZED,
                    response.providerReference(),
                    null,
                    latencyMs,
                    now));
            publisher.publish(
                    PaymentEvents.AUTHORIZED,
                    PaymentEvents.AUTHORIZED_ROUTING_KEY,
                    payment.getId(),
                    PaymentResultPayload.authorized(payment));
            log.info("Authorized payment {} for reservation {} in {} ms",
                    payment.getId(), payment.getReservationId(), latencyMs);
            return;
        }

        payment.reject(response.reason(), now);
        attempts.save(PaymentAttempt.record(
                payment,
                attemptNumber,
                PaymentAttemptOutcome.REJECTED,
                response.providerReference(),
                response.reason(),
                latencyMs,
                now));
        publisher.publish(
                PaymentEvents.REJECTED,
                PaymentEvents.REJECTED_ROUTING_KEY,
                payment.getId(),
                PaymentResultPayload.rejected(payment));
        log.info("Rejected payment {} for reservation {}: {}",
                payment.getId(), payment.getReservationId(), payment.getRejectionReason());
    }

    /**
     * Compensation of a late authorization. The refund is simulated, so it only moves the state
     * machine: an already refunded payment is a no-op and any other status is a permanent failure
     * that must not be retried.
     */
    @Transactional
    public void refund(RefundRequestedPayload request) {
        if (request.reservationId() == null) {
            throw new PermanentPaymentException("PaymentRefundRequested without a reservation");
        }
        Payment payment = payments
                .findByReservationId(request.reservationId())
                .orElseThrow(() -> new PermanentPaymentException(
                        "No payment for reservation " + request.reservationId()));
        if (request.paymentId() != null && !request.paymentId().equals(payment.getId())) {
            throw new PermanentPaymentException("Refund names payment " + request.paymentId()
                    + " but reservation " + request.reservationId() + " has " + payment.getId());
        }
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment {} is already refunded; ignoring the compensation", payment.getId());
            return;
        }
        payment.refund(clock.instant());
        log.info("Refunded payment {} for reservation {}: {}",
                payment.getId(), payment.getReservationId(), request.reason());
    }

    private static void validate(PaymentRequestedPayload request) {
        if (request.reservationId() == null || request.eventId() == null) {
            throw new PermanentPaymentException("PaymentRequested without reservation or event");
        }
        if (request.userId() == null || request.userId().isBlank()) {
            throw new PermanentPaymentException("PaymentRequested without a user");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new PermanentPaymentException("PaymentRequested with a non-positive amount");
        }
        if (request.currency() == null || request.currency().length() != 3) {
            throw new PermanentPaymentException("PaymentRequested without a three-letter currency");
        }
    }
}
