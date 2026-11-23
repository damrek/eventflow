import type { Reservation, ReservationStatus } from "../../api/booking";
import { isTerminalReservationStatus } from "../../api/booking";
import { Button } from "../../components/Button";
import { ErrorState } from "../../components/ErrorState";
import { formatDateTime, formatPrice, shortId } from "../../components/format";
import { StatusBadge } from "../../components/StatusBadge";
import type { BadgeTone } from "../../components/StatusBadge";
import { cardClass } from "../../components/ui";
import { Countdown } from "./Countdown";
import { useReservationActions } from "./useReservationActions";

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

const terminalMessages: Partial<Record<ReservationStatus, string>> = {
  CONFIRMED: "Payment confirmed",
  PAYMENT_FAILED: "The payment was rejected by the payment provider",
  EXPIRED: "The hold expired before the payment completed",
  CANCELLED: "Reservation cancelled",
};

export interface ReservationPanelProps {
  reservation: Reservation;
  userId: string;
  ticketNames: Map<string, string>;
  /** Present on the reserve page: a terminal state offers a new hold with a new idempotency key. */
  onRetry?: () => void;
}

export function ReservationPanel({ reservation, userId, ticketNames, onRetry }: ReservationPanelProps) {
  const { checkout, cancel } = useReservationActions(userId);
  const actionError = checkout.error ?? cancel.error;
  const terminal = isTerminalReservationStatus(reservation.status);
  const liveMessage =
    reservation.status === "PENDING_PAYMENT"
      ? "Payment pending — waiting for the payment service…"
      : terminalMessages[reservation.status];

  return (
    <section aria-labelledby="reservation-heading" className={`${cardClass} space-y-4`}>
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="reservation-heading" className="text-lg font-semibold text-slate-900">
          Reservation {shortId(reservation.reservationId)}
        </h2>
        <StatusBadge tone={statusTones[reservation.status]}>
          {statusLabels[reservation.status]}
        </StatusBadge>
      </header>

      <p className="text-sm text-slate-600">Created {formatDateTime(reservation.createdAt)}</p>

      <ul className="divide-y divide-slate-200">
        {reservation.items.map((item) => (
          <li
            key={item.ticketTypeId}
            className="flex items-center justify-between gap-3 py-2 text-sm text-slate-800"
          >
            <span>
              {ticketNames.get(item.ticketTypeId) ?? `Ticket type ${shortId(item.ticketTypeId)}`} ×{" "}
              {item.quantity}
            </span>
            <span className="font-medium">{formatPrice(item.unitPrice)} each</span>
          </li>
        ))}
      </ul>

      <p className="flex items-center justify-between border-t border-slate-200 pt-3 text-base font-semibold text-slate-900">
        <span>Total</span>
        <span>{formatPrice(reservation.totalAmount)}</span>
      </p>

      {reservation.status === "HELD" ? (
        <p className="text-sm text-slate-700">
          Time remaining: <Countdown until={reservation.expiresAt} />
        </p>
      ) : null}

      {liveMessage ? (
        <p
          role="status"
          aria-live="polite"
          className={`rounded-md px-3 py-2 text-sm ${
            reservation.status === "CONFIRMED"
              ? "bg-emerald-50 text-emerald-900"
              : reservation.status === "PENDING_PAYMENT"
                ? "bg-amber-50 text-amber-900"
                : "bg-slate-100 text-slate-800"
          }`}
        >
          {liveMessage}
        </p>
      ) : null}

      {actionError ? <ErrorState error={actionError} title="The action failed" /> : null}

      <div className="flex flex-wrap items-center gap-2">
        {reservation.status === "HELD" ? (
          <>
            <Button
              onClick={() => checkout.mutate(reservation.reservationId)}
              disabled={checkout.isPending}
            >
              {checkout.isPending ? "Starting payment…" : "Pay now"}
            </Button>
            <Button
              variant="danger"
              onClick={() => cancel.mutate(reservation.reservationId)}
              disabled={cancel.isPending}
            >
              {cancel.isPending ? "Cancelling…" : "Cancel reservation"}
            </Button>
          </>
        ) : null}

        {terminal && onRetry ? <Button onClick={onRetry}>Reserve again</Button> : null}
      </div>

      <p className="text-xs text-slate-500">
        The status above is the booking service&apos;s. Nothing is confirmed on the client.
      </p>
    </section>
  );
}
