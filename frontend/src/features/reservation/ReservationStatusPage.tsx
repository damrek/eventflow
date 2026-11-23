import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { Link, useParams } from "react-router";

import { getReservation } from "../../api/booking";
import type { Reservation } from "../../api/booking";
import { getEvent } from "../../api/catalog";
import { ErrorState } from "../../components/ErrorState";
import { LoadingState } from "../../components/LoadingState";
import { buttonClass, linkClass } from "../../components/ui";
import { queryKeys } from "../../hooks/queryKeys";
import { useUserId } from "../../hooks/useUserId";
import { reservationPollInterval } from "./polling";
import { ReservationPanel } from "./ReservationPanel";

export function ReservationStatusPage() {
  const { reservationId = "" } = useParams();
  const userId = useUserId();

  const reservationQuery = useQuery<Reservation>({
    queryKey: queryKeys.reservation(reservationId),
    queryFn: () => getReservation(reservationId, userId),
    enabled: reservationId !== "",
    refetchInterval: (query) => reservationPollInterval(query.state.data),
  });

  const eventId = reservationQuery.data?.eventId ?? "";
  const eventQuery = useQuery({
    queryKey: queryKeys.event(eventId),
    queryFn: () => getEvent(eventId),
    enabled: eventId !== "",
    retry: false,
  });

  const ticketNames = useMemo(() => {
    const names = new Map<string, string>();
    for (const ticketType of eventQuery.data?.ticketTypes ?? []) {
      names.set(ticketType.id, ticketType.name);
    }
    return names;
  }, [eventQuery.data]);

  return (
    <div className="space-y-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-bold text-slate-900">Reservation status</h1>
        <p className="text-sm text-slate-600">Live status reported by the booking service.</p>
      </header>

      {reservationQuery.isPending ? <LoadingState label="Loading the reservation…" /> : null}

      {reservationQuery.isError ? (
        <ErrorState
          error={reservationQuery.error}
          title="The reservation could not be loaded"
          onRetry={() => void reservationQuery.refetch()}
        />
      ) : null}

      {reservationQuery.data ? (
        <ReservationPanel
          reservation={reservationQuery.data}
          userId={userId}
          ticketNames={ticketNames}
        />
      ) : null}

      <p className="text-sm">
        <Link to="/reservations" className={linkClass}>
          Back to my reservations
        </Link>
      </p>
      <p className="text-sm">
        <Link className={buttonClass("secondary")} to="/">
          Browse events
        </Link>
      </p>
    </div>
  );
}
