package com.eventflow.payment.payment;

/** Event names and routing keys Payment produces and consumes. */
public final class PaymentEvents {

    public static final String REQUESTED = "PaymentRequested";
    public static final String REQUESTED_ROUTING_KEY = "payment.requested";

    public static final String REFUND_REQUESTED = "PaymentRefundRequested";
    public static final String REFUND_REQUESTED_ROUTING_KEY = "payment.refund-requested";

    public static final String AUTHORIZED = "PaymentAuthorized";
    public static final String AUTHORIZED_ROUTING_KEY = "payment.authorized";

    public static final String REJECTED = "PaymentRejected";
    public static final String REJECTED_ROUTING_KEY = "payment.rejected";

    private PaymentEvents() {
    }
}
