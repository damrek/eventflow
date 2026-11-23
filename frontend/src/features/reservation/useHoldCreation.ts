import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";

import type { ApiError } from "../../api/client";
import { toApiError } from "../../api/client";
import type { Reservation } from "../../api/booking";
import { createReservation } from "../../api/booking";
import { randomUuid } from "../../api/identity";
import { queryKeys } from "../../hooks/queryKeys";
import type { SelectedItem } from "./items";

export interface HoldState {
  status: "idle" | "creating" | "created" | "error";
  reservation: Reservation | null;
  error: ApiError | null;
}

interface UseHoldCreationOptions {
  eventId: string;
  userId: string;
  items: SelectedItem[];
  /** Stable serialization of `items` so the creation runs once per selection. */
  itemsKey: string;
  admissionToken: string | null;
  enabled: boolean;
}

/**
 * Creates the hold exactly once per attempt with one `Idempotency-Key`. `retry` mints a new key:
 * after a terminal state the previous reservation must not be reused.
 */
export function useHoldCreation({
  eventId,
  userId,
  items,
  itemsKey,
  admissionToken,
  enabled,
}: UseHoldCreationOptions) {
  const queryClient = useQueryClient();
  const idempotencyKey = useRef<string | undefined>(undefined);
  if (idempotencyKey.current === undefined) {
    idempotencyKey.current = randomUuid();
  }
  const [generation, setGeneration] = useState(0);
  const [state, setState] = useState<HoldState>({ status: "idle", reservation: null, error: null });

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    const key = idempotencyKey.current ?? randomUuid();
    idempotencyKey.current = key;
    setState({ status: "creating", reservation: null, error: null });

    createReservation({ eventId, userId, idempotencyKey: key, admissionToken, items })
      .then((reservation) => {
        if (cancelled) return;
        queryClient.setQueryData(queryKeys.reservation(reservation.reservationId), reservation);
        setState({ status: "created", reservation, error: null });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        setState({ status: "error", reservation: null, error: toApiError(error) });
      });

    return () => {
      cancelled = true;
    };
  }, [enabled, generation, eventId, userId, itemsKey, admissionToken, items, queryClient]);

  const retry = useCallback(() => {
    idempotencyKey.current = randomUuid();
    setGeneration((value) => value + 1);
  }, []);

  return { state, retry };
}
