package com.eventflow.notification.notification;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Wire contract of the booking events Notification consumes. Only what the mailbox needs is
 * declared; unknown fields are accepted so the producer can extend the payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingEventPayload(UUID reservationId, UUID eventId, String userId) {

    /** Envelope version this consumer understands. */
    public static final int VERSION = 1;
}
