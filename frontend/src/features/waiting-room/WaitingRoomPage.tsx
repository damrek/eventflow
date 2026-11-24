import { useEffect } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router";

import { isWaitingRoomUnavailable } from "../../api/booking";
import { ErrorState } from "../../components/ErrorState";
import { LoadingState } from "../../components/LoadingState";
import { buttonClass, cardClass } from "../../components/ui";
import { useUserId } from "../../hooks/useUserId";
import { useWaitingRoomJoin } from "./useWaitingRoomJoin";
import { useWaitingRoomStatus } from "./useWaitingRoomStatus";

export function WaitingRoomPage() {
  const { eventId = "" } = useParams();
  const location = useLocation();
  // The raw search string is forwarded verbatim so the `item=` values keep their shape.
  const search = location.search.replace(/^\?/, "");
  const navigate = useNavigate();
  const userId = useUserId();

  const { state: join, retry } = useWaitingRoomJoin(eventId, userId);
  const joined = join.status === "joined";

  const statusQuery = useWaitingRoomStatus({ eventId, userId, enabled: joined, poll: true });

  const waitingRoom = statusQuery.data ?? (join.status === "joined" ? join.data : null);
  const admitted = waitingRoom?.status === "ADMITTED";

  const reserveHref = `/events/${eventId}/reserve${search ? `?${search}` : ""}`;

  useEffect(() => {
    if (!admitted) return;
    navigate(reserveHref, { replace: true });
  }, [admitted, navigate, reserveHref]);

  const joinUnavailable = join.status === "unavailable";

  return (
    <div className="space-y-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-bold text-slate-900">Waiting room</h1>
        <p className="text-sm text-slate-600">
          Admission is decided by the booking service. This page only reports it.
        </p>
      </header>

      {join.status === "joining" ? <LoadingState label="Joining the queue…" /> : null}

      {join.status === "failed" ? (
        <ErrorState error={join.error} title="You could not join the queue" onRetry={retry} />
      ) : null}

      {joinUnavailable ? (
        <div
          role="alert"
          className="space-y-3 rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"
        >
          <p className="font-semibold">The waiting room is unavailable</p>
          <p>
            The booking service cannot read the queue right now, so it allows reserving directly.
            Stock is still enforced when the reservation is created.
          </p>
          <Link className={buttonClass("secondary")} to={reserveHref}>
            Continue without waiting
          </Link>
        </div>
      ) : null}

      {joined && statusQuery.isError && !isWaitingRoomUnavailable(statusQuery.error) ? (
        <ErrorState
          error={statusQuery.error}
          title="Your queue position could not be refreshed"
          onRetry={() => void statusQuery.refetch()}
        />
      ) : null}

      {joined && waitingRoom ? (
        <section aria-labelledby="queue-heading" className={`${cardClass} space-y-3`}>
          <h2 id="queue-heading" className="text-lg font-semibold text-slate-900">
            Queue status
          </h2>

          <div aria-live="polite">
            {waitingRoom.status === "WAITING" ? (
              <div className="space-y-1">
                <p className="text-base font-semibold text-amber-900">You are in the queue</p>
                <p className="text-sm text-slate-700">
                  Position {waitingRoom.position ?? "—"} · {waitingRoom.waitingCount} waiting
                </p>
                <p className="text-xs text-slate-600">This page refreshes every two seconds.</p>
              </div>
            ) : null}

            {waitingRoom.status === "ADMITTED" ? (
              <div className="space-y-1">
                <p className="text-base font-semibold text-emerald-800">You are admitted</p>
                <p className="text-sm text-slate-700">Taking you to your reservation…</p>
              </div>
            ) : null}

            {waitingRoom.status === "NOT_JOINED" ? (
              <div className="space-y-2">
                <p className="text-base font-semibold text-slate-800">You are not in the queue</p>
                <p className="text-sm text-slate-700">
                  {waitingRoom.waitingCount} waiting. Join again to get a new position.
                </p>
                <button type="button" className={buttonClass("secondary")} onClick={retry}>
                  Join the queue
                </button>
              </div>
            ) : null}
          </div>

          <p className="text-xs text-slate-500">
            Queue admission is a throttle; inventory is decided by the booking service.
          </p>
        </section>
      ) : null}

      <p className="text-sm">
        <Link className={buttonClass("secondary")} to={`/events/${eventId}`}>
          Back to the event
        </Link>
      </p>
    </div>
  );
}
