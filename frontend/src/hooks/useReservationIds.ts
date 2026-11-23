import { useCallback, useEffect, useState } from "react";

/**
 * Only reservation ids live in the browser. Everything shown about them comes from Booking.
 */
export const RESERVATION_IDS_STORAGE_KEY = "eventflow.reservations";

const RESERVATION_IDS_CHANGED = "eventflow:reservations-changed";

export function readReservationIds(): string[] {
  const raw = window.localStorage.getItem(RESERVATION_IDS_STORAGE_KEY);
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((value): value is string => typeof value === "string");
  } catch {
    return [];
  }
}

export function rememberReservationId(reservationId: string): void {
  const ids = readReservationIds();
  if (ids.includes(reservationId)) return;
  window.localStorage.setItem(
    RESERVATION_IDS_STORAGE_KEY,
    JSON.stringify([reservationId, ...ids]),
  );
  window.dispatchEvent(new Event(RESERVATION_IDS_CHANGED));
}

/** Live list of remembered ids, kept in sync across mounts and browser tabs. */
export function useReservationIds(): string[] {
  const [ids, setIds] = useState<string[]>(readReservationIds);

  const sync = useCallback(() => setIds(readReservationIds()), []);

  useEffect(() => {
    window.addEventListener(RESERVATION_IDS_CHANGED, sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener(RESERVATION_IDS_CHANGED, sync);
      window.removeEventListener("storage", sync);
    };
  }, [sync]);

  return ids;
}
