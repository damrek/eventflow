import type { QueryKey } from "@tanstack/react-query";

/**
 * Every server read has exactly one key. Reservation ids come from the backend, so the key
 * doubles as the cache entry the payment poll rewrites.
 */
export const queryKeys = {
  events: (page: number): QueryKey => ["catalog", "events", page],
  event: (eventId: string): QueryKey => ["catalog", "event", eventId],
  availability: (eventId: string): QueryKey => ["booking", "availability", eventId],
  waitingRoom: (eventId: string, userId: string): QueryKey => [
    "booking",
    "waiting-room",
    eventId,
    userId,
  ],
  reservation: (reservationId: string | null): QueryKey => [
    "booking",
    "reservation",
    reservationId,
  ],
  notifications: (userId: string, page: number): QueryKey => ["notification", userId, page],
};
