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

interface CreationResult {
  /** The request this answer belongs to, so a stale answer never describes the current attempt. */
  forRequest: string;
  state: Exclude<HoldState, { status: "creating" | "idle" }>;
}

const IDLE: HoldState = { status: "idle", reservation: null, error: null };
const CREATING: HoldState = { status: "creating", reservation: null, error: null };

/**
 * Creates the hold exactly once per attempt with one `Idempotency-Key`. `retry` mints a new key:
 * after a terminal state the previous reservation must not be reused.
 *
 * The pending state is derived during render from the attempt in flight instead of being set from
 * the effect body, so an attempt never renders as `creating` for a request that has not left yet.
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
  const [result, setResult] = useState<CreationResult | null>(null);
  const forRequest = `${generation}|${eventId}|${userId}|${itemsKey}|${admissionToken ?? ""}`;
  const state: HoldState =
    result?.forRequest === forRequest ? result.state : enabled ? CREATING : IDLE;

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    const key = idempotencyKey.current ?? randomUuid();
    idempotencyKey.current = key;

    createReservation({ eventId, userId, idempotencyKey: key, admissionToken, items })
      .then((reservation) => {
        if (cancelled) return;
        queryClient.setQueryData(queryKeys.reservation(reservation.reservationId), reservation);
        setResult({
          forRequest,
          state: { status: "created", reservation, error: null },
        });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        setResult({
          forRequest,
          state: { status: "error", reservation: null, error: toApiError(error) },
        });
      });

    return () => {
      cancelled = true;
    };
  }, [enabled, eventId, userId, itemsKey, admissionToken, items, queryClient, forRequest]);

  const retry = useCallback(() => {
    // After a terminal state the previous reservation must not be reused.
    idempotencyKey.current = randomUuid();
    setGeneration((value) => value + 1);
  }, []);

  const retrySameKey = useCallback(() => {
    // A creation error may have hidden a successful request; replaying with the same key is the
    // idempotent path and cannot hold twice.
    setGeneration((value) => value + 1);
  }, []);

  return { state, retry, retrySameKey };
}
