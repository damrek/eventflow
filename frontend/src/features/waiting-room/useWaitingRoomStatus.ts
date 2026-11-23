import { useQuery } from "@tanstack/react-query";

import { getWaitingRoomStatus } from "../../api/booking";
import { queryKeys } from "../../hooks/queryKeys";

/** The waiting room is polled every two seconds while the user waits (spec S10). */
export const WAITING_ROOM_POLL_MS = 2000;

interface UseWaitingRoomStatusOptions {
  eventId: string;
  userId: string;
  enabled: boolean;
  /** The reserve page only needs one read to recover the admission token. */
  poll: boolean;
}

export function useWaitingRoomStatus({ eventId, userId, enabled, poll }: UseWaitingRoomStatusOptions) {
  return useQuery({
    queryKey: queryKeys.waitingRoom(eventId, userId),
    queryFn: () => getWaitingRoomStatus(eventId, userId),
    enabled,
    retry: false,
    refetchInterval: poll
      ? (query) => (query.state.data?.status === "ADMITTED" ? false : WAITING_ROOM_POLL_MS)
      : false,
  });
}
