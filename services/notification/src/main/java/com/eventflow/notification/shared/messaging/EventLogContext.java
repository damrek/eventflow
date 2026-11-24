package com.eventflow.notification.shared.messaging;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Puts the ids of the event being handled into the MDC for the duration of the handler, so a log
 * line from the consumer can be traced back to the reservation it touched. Closing the scope
 * removes them: the consumer thread is reused, and a stale id would attach to the next event.
 *
 * <p>The values are ids, never payload contents; nothing here can leak a name or an amount.
 */
public final class EventLogContext implements AutoCloseable {

    public static final String RESERVATION_ID = "reservationId";
    public static final String EVENT_ID = "eventId";

    private EventLogContext() {
    }

    /** Absent arguments are simply not put: a log line never carries the string "null". */
    public static EventLogContext open(UUID eventId, UUID reservationId) {
        if (eventId != null) {
            MDC.put(EVENT_ID, eventId.toString());
        }
        if (reservationId != null) {
            MDC.put(RESERVATION_ID, reservationId.toString());
        }
        return new EventLogContext();
    }

    @Override
    public void close() {
        MDC.remove(EVENT_ID);
        MDC.remove(RESERVATION_ID);
    }
}
