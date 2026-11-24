package com.eventflow.booking.shared.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * S4 of Phase 7: a consumer log line must identify the event and the reservation it touched, and
 * must not leave those ids on the thread for the next message.
 */
class EventLogContextTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID = UUID.randomUUID();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void exposesTheIdsOnlyWhileTheHandlerRuns() {
        try (EventLogContext logs = EventLogContext.open(EVENT_ID, RESERVATION_ID)) {
            assertThat(MDC.get(EventLogContext.EVENT_ID)).isEqualTo(EVENT_ID.toString());
            assertThat(MDC.get(EventLogContext.RESERVATION_ID)).isEqualTo(RESERVATION_ID.toString());
        }

        assertThat(MDC.get(EventLogContext.EVENT_ID)).isNull();
        assertThat(MDC.get(EventLogContext.RESERVATION_ID)).isNull();
    }

    @Test
    void clearsTheIdsWhenTheHandlerThrows() {
        assertThatThrownBy(() -> {
            try (EventLogContext logs = EventLogContext.open(EVENT_ID, RESERVATION_ID)) {
                throw new IllegalStateException("handler failed");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(EventLogContext.EVENT_ID)).isNull();
        assertThat(MDC.get(EventLogContext.RESERVATION_ID)).isNull();
    }

    @Test
    void omitsAnIdItDoesNotHave() {
        try (EventLogContext logs = EventLogContext.open(EVENT_ID, null)) {
            assertThat(MDC.get(EventLogContext.EVENT_ID)).isEqualTo(EVENT_ID.toString());
            assertThat(MDC.get(EventLogContext.RESERVATION_ID)).isNull();
        }
    }
}
