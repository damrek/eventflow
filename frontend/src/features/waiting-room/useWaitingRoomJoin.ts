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

/**
 * `join` inserts the user once with a server-assigned score, so a repeated call while waiting keeps
 * the original position. A 503 is not an error to retry blindly: the gate fails open and the user
 * may book directly.
 */
export function useWaitingRoomJoin(eventId: string, userId: string) {
  const [state, setState] = useState<JoinState>({ status: "joining" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setState({ status: "joining" });
    joinWaitingRoom(eventId, userId)
      .then((data) => {
        if (!cancelled) setState({ status: "joined", data });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        const apiError = toApiError(error);
        if (isWaitingRoomUnavailable(apiError)) {
          setState({ status: "unavailable", error: apiError });
        } else {
          setState({ status: "failed", error: apiError });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [eventId, userId, attempt]);

  const retry = useCallback(() => setAttempt((value) => value + 1), []);

  return { state, retry };
}
