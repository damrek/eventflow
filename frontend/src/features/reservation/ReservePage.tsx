import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router";

import type { Reservation } from "../../api/booking";
import { getReservation, isWaitingRoomUnavailable } from "../../api/booking";
import { getEvent } from "../../api/catalog";
import { EmptyState } from "../../components/EmptyState";
import { ErrorState } from "../../components/ErrorState";
import { LoadingState } from "../../components/LoadingState";
import { buttonClass } from "../../components/ui";
import { queryKeys } from "../../hooks/queryKeys";
import { rememberReservationId } from "../../hooks/useReservationIds";
import { useUserId } from "../../hooks/useUserId";
import { useWaitingRoomStatus } from "../waiting-room/useWaitingRoomStatus";
import { parseSelectedItems, selectedItemsKey } from "./items";
import { reservationPollInterval } from "./polling";
import { ReservationPanel } from "./ReservationPanel";
import { useHoldCreation } from "./useHoldCreation";

export function ReservePage() {
  const { eventId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const location = useLocation();
  // Forwarded verbatim: the reserve page must rebuild exactly the waiting-room URL.
  const search = location.search.replace(/^\?/, "");
  const navigate = useNavigate();
  const userId = useUserId();

  const items = useMemo(() => parseSelectedItems(searchParams), [searchParams]);
  const itemsKey = useMemo(() => selectedItemsKey(items), [items]);

  const eventQuery = useQuery({
    queryKey: queryKeys.event(eventId),
    queryFn: () => getEvent(eventId),
    enabled: eventId !== "",
    retry: false,
  });

  const statusQuery = useWaitingRoomStatus({
    eventId,
    userId,
    enabled: eventId !== "" && items.length > 0,
    poll: false,
  });

  const admissionUnavailable = isWaitingRoomUnavailable(statusQuery.error);
  const admitted = statusQuery.data?.status === "ADMITTED";
  const notAdmitted = statusQuery.isSuccess && !admitted;
  const admissionToken = admitted ? (statusQuery.data?.token ?? null) : null;

  const waitingRoomHref = `/events/${eventId}/waiting-room${search ? `?${search}` : ""}`;

  useEffect(() => {
    if (!notAdmitted) return;
    navigate(waitingRoomHref, { replace: true });
  }, [notAdmitted, waitingRoomHref, navigate]);

  // Only an admitted user (or a room that is down and therefore fails open) may create the hold.
  const gateReady = items.length > 0 && (admitted || admissionUnavailable);
  const hold = useHoldCreation({
    eventId,
    userId,
    items,
    itemsKey,
    admissionToken,
    enabled: gateReady,
  });

  const created = hold.state.reservation;
  useEffect(() => {
    if (created) rememberReservationId(created.reservationId);
  }, [created]);

  const reservationId = created?.reservationId ?? null;
  const reservationQuery = useQuery<Reservation>({
    queryKey: queryKeys.reservation(reservationId),
    queryFn: () => getReservation(reservationId ?? "", userId),
    enabled: reservationId !== null,
    refetchInterval: (query) => reservationPollInterval(query.state.data),
  });
  const reservation = reservationQuery.data ?? created;

  const ticketNames = useMemo(() => {
    const names = new Map<string, string>();
    for (const ticketType of eventQuery.data?.ticketTypes ?? []) {
      names.set(ticketType.id, ticketType.name);
    }
    return names;
  }, [eventQuery.data]);

  if (items.length === 0) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold text-slate-900">Reserve tickets</h1>
        <EmptyState title="No tickets were selected">
          <Link className={buttonClass("secondary")} to={`/events/${eventId}`}>
            Choose your tickets
          </Link>
        </EmptyState>
      </div>
    );
  }

  const holdError = hold.state.error;

  return (
    <div className="space-y-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-bold text-slate-900">Reserve tickets</h1>
        <p className="text-sm text-slate-600">
          {eventQuery.data ? `${eventQuery.data.name} · ${eventQuery.data.venueName}` : "Event"}
        </p>
      </header>

      {admissionUnavailable ? (
        <div className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
          <p className="font-semibold">The waiting room is unavailable</p>
          <p className="mt-1">
            Reserving directly is allowed while the queue is down. Stock is still enforced by the
            booking service.
          </p>
        </div>
      ) : null}

      {statusQuery.isPending ? <LoadingState label="Checking your admission…" /> : null}

      {statusQuery.isError && !admissionUnavailable ? (
        <ErrorState
          error={statusQuery.error}
          title="Your admission could not be checked"
          onRetry={() => void statusQuery.refetch()}
        />
      ) : null}

      {notAdmitted ? (
        <LoadingState label="You are not admitted yet — returning to the waiting room…" />
      ) : null}

      {hold.state.status === "creating" ? <LoadingState label="Holding your tickets…" /> : null}

      {holdError ? (
        <div className="space-y-3">
          <ErrorState
            error={holdError}
            title="The reservation could not be created"
            onRetry={holdError.code === "NOT_ADMITTED" ? undefined : hold.retrySameKey}
          />
          {holdError.code === "NOT_ADMITTED" ? (
            <p>
              <Link className={buttonClass("secondary")} to={waitingRoomHref}>
                Back to the waiting room
              </Link>
            </p>
          ) : null}
        </div>
      ) : null}

      {reservation ? (
        <ReservationPanel
          reservation={reservation}
          userId={userId}
          ticketNames={ticketNames}
          onRetry={hold.retry}
        />
      ) : null}
    </div>
  );
}
