import { useQuery } from "@tanstack/react-query";

import type { NotificationType } from "../../api/notifications";
import { listNotifications } from "../../api/notifications";
import { EmptyState } from "../../components/EmptyState";
import { ErrorState } from "../../components/ErrorState";
import { formatDateTime } from "../../components/format";
import { LoadingState } from "../../components/LoadingState";
import { StatusBadge } from "../../components/StatusBadge";
import { cardClass } from "../../components/ui";
import { queryKeys } from "../../hooks/queryKeys";

const typeLabels: Record<NotificationType, string> = {
  BOOKING_CONFIRMED: "Booking confirmed",
  BOOKING_CANCELLED: "Booking cancelled",
  BOOKING_EXPIRED: "Booking expired",
  BOOKING_PAYMENT_FAILED: "Payment failed",
};

export function NotificationMailbox({ userId }: { userId: string }) {
  const query = useQuery({
    queryKey: queryKeys.notifications(userId, 0),
    queryFn: () => listNotifications(userId),
  });

  return (
    <section aria-labelledby="mailbox-heading" className={`${cardClass} space-y-3`}>
      <h2 id="mailbox-heading" className="text-lg font-semibold text-slate-900">
        Notifications
      </h2>
      <p className="text-xs text-slate-600">Messages written by the notification service.</p>

      {query.isPending ? <LoadingState label="Loading notifications…" /> : null}

      {query.isError ? (
        <ErrorState
          error={query.error}
          title="The mailbox could not be loaded"
          onRetry={() => void query.refetch()}
        />
      ) : null}

      {query.isSuccess && query.data.content.length === 0 ? (
        <EmptyState title="No notifications yet" />
      ) : null}

      {query.isSuccess && query.data.content.length > 0 ? (
        <ul className="space-y-2">
          {query.data.content.map((message) => (
            <li key={message.id} className="rounded-md border border-slate-200 p-3">
              <div className="flex flex-wrap items-center gap-2">
                <StatusBadge tone={message.status === "UNREAD" ? "pending" : "neutral"}>
                  {message.status === "UNREAD" ? "Unread" : "Read"}
                </StatusBadge>
                <span className="text-xs font-medium text-slate-700">
                  {typeLabels[message.type]}
                </span>
                <span className="text-xs text-slate-500">{formatDateTime(message.createdAt)}</span>
              </div>
              <p className="mt-1 text-sm text-slate-800">{message.content}</p>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
