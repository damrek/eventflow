package com.eventflow.payment.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Wire contract of {@code PaymentRequested} as Payment reads it. Booking owns the producer side;
 * this record is Payment's own copy so the two services never share classes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentRequestedPayload(
        UUID reservationId,
        UUID eventId,
        String userId,
        BigDecimal amount,
        String currency,
        List<Item> items) {

    /** Envelope version this consumer understands. */
    public static final int VERSION = 1;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(UUID ticketTypeId, int quantity) {
    }
}
