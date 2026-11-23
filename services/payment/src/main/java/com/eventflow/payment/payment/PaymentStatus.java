package com.eventflow.payment.payment;

/**
 * Lifecycle of one payment. {@code REFUNDED} is the compensation state: it can only be reached
 * from {@code AUTHORIZED}, which is what makes a late authorization refundable exactly once.
 */
public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    REJECTED,
    REFUNDED
}
