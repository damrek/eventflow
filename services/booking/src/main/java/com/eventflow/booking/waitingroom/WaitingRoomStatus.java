package com.eventflow.booking.waitingroom;

/**
 * Public state of a user inside an event's waiting room. {@code NOT_JOINED} covers both "never
 * joined" and "the queue was lost", so the client always has a state to render.
 */
public enum WaitingRoomStatus {

    NOT_JOINED,
    WAITING,
    ADMITTED
}
