package com.eventflow.payment.payment;

/**
 * A failure that retrying cannot fix: a malformed payload or an illegal state transition. The
 * listener turns it into a rejection without requeue so the message ends in the DLQ.
 */
public class PermanentPaymentException extends RuntimeException {

    public PermanentPaymentException(String message) {
        super(message);
    }
}
