import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { useParams } from "react-router";

import { getAvailability } from "../../api/booking";
import { getEvent } from "../../api/catalog";
import { EmptyState } from "../../components/EmptyState";
import { ErrorState } from "../../components/ErrorState";
import { formatDateTime, formatPrice } from "../../components/format";
import { LoadingState } from "../../components/LoadingState";
import { StatusBadge } from "../../components/StatusBadge";
import { cardClass } from "../../components/ui";
import { queryKeys } from "../../hooks/queryKeys";
import type { TicketOption } from "./selectionSchema";
import { SelectionForm } from "./SelectionForm";

const AVAILABILITY_REFRESH_MS = 5000;

export function EventDetailPage() {
  const { eventId = "" } = useParams();
  const enabled = eventId !== "";

  const eventQuery = useQuery({
    queryKey: queryKeys.event(eventId),
    queryFn: () => getEvent(eventId),
    enabled,
  });

  const availabilityQuery = useQuery({
    queryKey: queryKeys.availability(eventId),
    queryFn: () => getAvailability(eventId),
    enabled,
    refetchInterval: AVAILABILITY_REFRESH_MS,
  });

  const options = useMemo<TicketOption[]>(() => {
    const ticketTypes = eventQuery.data?.ticketTypes ?? [];
    const rows = availabilityQuery.data?.ticketTypes ?? [];
    return ticketTypes.map((ticketType) => {
      const row = rows.find((candidate) => candidate.ticketTypeId === ticketType.id);
      return {
        id: ticketType.id,
        name: ticketType.name,
        price: row?.price ?? ticketType.price,
        capacity: row?.capacity ?? ticketType.capacity,
        available: row?.available ?? 0,
      };
    });
  }, [eventQuery.data, availabilityQuery.data]);

  const sellable = options.filter((option) => option.available > 0);
  const soldOut = options.filter((option) => option.available === 0);

  if (eventQuery.isPending) {
    return <LoadingState label="Loading the event…" />;
  }

  if (eventQuery.isError) {
    return (
      <ErrorState
        error={eventQuery.error}
        title="The event could not be loaded"
        onRetry={() => void eventQuery.refetch()}
      />
    );
  }

  const event = eventQuery.data;

  return (
    <div className="space-y-8">
      <header className="space-y-2">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold text-slate-900">{event.name}</h1>
          <StatusBadge tone={event.status === "PUBLISHED" ? "positive" : "neutral"}>
            {event.status === "PUBLISHED" ? "On sale" : "Draft"}
          </StatusBadge>
        </div>
        <p className="text-sm text-slate-600">
          {event.venueName} · {formatDateTime(event.startsAt)} · {event.totalCapacity} seats
        </p>
      </header>

      <section aria-labelledby="availability-heading" className={`${cardClass} space-y-3`}>
        <h2 id="availability-heading" className="text-lg font-semibold text-slate-900">
          Availability
        </h2>
        <p className="text-xs text-slate-600">Live inventory from the booking service.</p>

        {availabilityQuery.isPending ? <LoadingState label="Loading availability…" /> : null}

        {availabilityQuery.isError ? (
          <ErrorState
            error={availabilityQuery.error}
            title="Availability could not be loaded"
            onRetry={() => void availabilityQuery.refetch()}
          />
        ) : null}

        {availabilityQuery.isSuccess ? (
          availabilityQuery.data.ticketTypes.length === 0 ? (
            <EmptyState title="This event is not on sale yet" />
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
                    <th scope="col" className="py-2 pr-4 font-medium">Ticket type</th>
                    <th scope="col" className="py-2 pr-4 font-medium">Price</th>
                    <th scope="col" className="py-2 pr-4 font-medium">Available</th>
                    <th scope="col" className="py-2 pr-4 font-medium">Held</th>
                    <th scope="col" className="py-2 pr-4 font-medium">Sold</th>
                    <th scope="col" className="py-2 font-medium">Capacity</th>
                  </tr>
                </thead>
                <tbody>
                  {availabilityQuery.data.ticketTypes.map((row) => {
                    const name =
                      event.ticketTypes.find((ticketType) => ticketType.id === row.ticketTypeId)
                        ?.name ?? row.ticketTypeId;
                    return (
                      <tr key={row.ticketTypeId} className="border-b border-slate-100 last:border-0">
                        <td className="py-2 pr-4 font-medium text-slate-900">{name}</td>
                        <td className="py-2 pr-4">{formatPrice(row.price)}</td>
                        <td className="py-2 pr-4">{row.available}</td>
                        <td className="py-2 pr-4">{row.held}</td>
                        <td className="py-2 pr-4">{row.sold}</td>
                        <td className="py-2">{row.capacity}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )
        ) : null}
      </section>

      <section aria-labelledby="selection-heading" className={`${cardClass} space-y-4`}>
        <h2 id="selection-heading" className="text-lg font-semibold text-slate-900">
          Choose your tickets
        </h2>

        {availabilityQuery.isSuccess ? (
          sellable.length === 0 ? (
            <EmptyState title="No ticket type has stock right now" />
          ) : (
            <SelectionForm eventId={event.id} options={sellable} />
          )
        ) : (
          <p className="text-sm text-slate-600">Ticket selection appears once availability loads.</p>
        )}

        {soldOut.length > 0 ? (
          <p className="text-xs text-slate-600">
            Sold out: {soldOut.map((option) => option.name).join(", ")}
          </p>
        ) : null}
      </section>
    </div>
  );
}
