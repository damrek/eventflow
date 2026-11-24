import { useCallback, useEffect, useState } from "react";

import type { ApiError } from "../../api/client";
import { toApiError } from "../../api/client";
import type { WaitingRoom } from "../../api/booking";
import { isWaitingRoomUnavailable, joinWaitingRoom } from "../../api/booking";

export type JoinState =
  | { status: "joining" }
  | { status: "joined"; data: WaitingRoom }
  | { status: "unavailable"; error: ApiError }
  | { status: "failed"; error: ApiError };

interface JoinResult {
  /** The attempt this answer belongs to, so a stale answer never describes the current join. */
  forAttempt: string;
  state: Exclude<JoinState, { status: "joining" }>;
}

/**
 * `join` inserts the user once with a server-assigned score, so a repeated call while waiting keeps
 * the original position. A 503 is not an error to retry blindly: the gate fails open and the user
 * may book directly.
 *
 * The pending state is derived during render from the attempt that has been requested, never set
 * from the effect body: a synchronous `setState` in an effect renders twice before the request
 * leaves and hides which attempt the answer belongs to.
 */
export function useWaitingRoomJoin(eventId: string, userId: string) {
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<JoinResult | null>(null);
  const forAttempt = `${eventId}|${userId}|${attempt}`;
  const state: JoinState = result?.forAttempt === forAttempt ? result.state : { status: "joining" };

  useEffect(() => {
    let cancelled = false;
    joinWaitingRoom(eventId, userId)
      .then((data) => {
        if (!cancelled) setResult({ forAttempt, state: { status: "joined", data } });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        const apiError = toApiError(error);
        setResult({
          forAttempt,
          state: isWaitingRoomUnavailable(apiError)
            ? { status: "unavailable", error: apiError }
            : { status: "failed", error: apiError },
        });
      });
    return () => {
      cancelled = true;
    };
  }, [eventId, userId, forAttempt]);

  const retry = useCallback(() => setAttempt((value) => value + 1), []);

  return { state, retry };
}
