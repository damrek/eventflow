import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";

import { getReservation } from "../../api/booking";
import type { Reservation, ReservationStatus } from "../../api/booking";
import { EmptyState } from "../../components/EmptyState";
import { formatDateTime, formatPrice, shortId } from "../../components/format";
import { LoadingState } from "../../components/LoadingState";
import { StatusBadge } from "../../components/StatusBadge";
import type { BadgeTone } from "../../components/StatusBadge";
import { buttonClass, cardClass, linkClass } from "../../components/ui";
import { queryKeys } from "../../hooks/queryKeys";
import { useReservationIds } from "../../hooks/useReservationIds";
import { useUserId } from "../../hooks/useUserId";
import { NotificationMailbox } from "../notifications/NotificationMailbox";
import { reservationPollInterval } from "../reservation/polling";

const statusLabels: Record<ReservationStatus, string> = {
  HELD: "Held",
  PENDING_PAYMENT: "Payment pending",
  CONFIRMED: "Confirmed",
  PAYMENT_FAILED: "Payment failed",
  EXPIRED: "Expired",
  CANCELLED: "Cancelled",
};

const statusTones: Record<ReservationStatus, BadgeTone> = {
  HELD: "pending",
  PENDING_PAYMENT: "pending",
  CONFIRMED: "positive",
  PAYMENT_FAILED: "negative",
  EXPIRED: "negative",
  CANCELLED: "neutral",
};

function ReservationRow({ reservationId, userId }: { reservationId: string; userId: string }) {
  const query = useQuery<Reservation>({
    queryKey: queryKeys.reservation(reservationId),
    queryFn: () => getReservation(reservationId, userId),
    refetchInterval: (candidate) => reservationPollInterval(candidate.state.data),
  });

  if (query.isPending) {
    return (
      <div className={cardClass}>
        <LoadingState label="Loading reservation…" />
      </div>
    );
  }

  if (query.isError || !query.data) {
    return (
      <div className={`${cardClass} space-y-1`}>
        <p className="text-sm font-medium text-slate-900">Reservation {shortId(reservationId)}</p>
        <p className="text-sm text-rose-800">
          This reservation is no longer available to this demo identity.
        </p>
      </div>
    );
  }

  const reservation = query.data;

  return (
    <div className={`${cardClass} flex flex-wrap items-center justify-between gap-3`}>
      <div className="space-y-1">
        <p className="text-sm font-semibold text-slate-900">
          <Link to={`/reservations/${reservation.reservationId}`} className={linkClass}>
            Reservation {shortId(reservation.reservationId)}
          </Link>
        </p>
        <p className="text-xs text-slate-600">
          Created {formatDateTime(reservation.createdAt)} · {formatPrice(reservation.totalAmount)}
        </p>
      </div>
      <StatusBadge tone={statusTones[reservation.status]}>
        {statusLabels[reservation.status]}
      </StatusBadge>
    </div>
  );
}

export function MyReservationsPage() {
  const userId = useUserId();
  const reservationIds = useReservationIds();

  return (
    <div className="space-y-8">
      <section aria-labelledby="my-reservations-heading" className="space-y-4">
        <header className="space-y-1">
          <h1 id="my-reservations-heading" className="text-2xl font-bold text-slate-900">
            My reservations
          </h1>
          <p className="text-sm text-slate-600">
            This browser keeps only the reservation ids. Every status comes from the booking
            service.
          </p>
          <p className="text-xs text-slate-600">
            Demo identity <span className="font-mono">{userId}</span> — a browser-local value, not
            authentication.
          </p>
        </header>

        {reservationIds.length === 0 ? (
          <EmptyState title="No reservations in this browser yet">
            <Link className={buttonClass("secondary")} to="/">
              Browse events
            </Link>
          </EmptyState>
        ) : (
          <ul className="space-y-3">
            {reservationIds.map((reservationId) => (
              <li key={reservationId}>
                <ReservationRow reservationId={reservationId} userId={userId} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <NotificationMailbox userId={userId} />
    </div>
  );
}
