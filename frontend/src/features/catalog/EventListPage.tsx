import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";

import { listEvents } from "../../api/catalog";
import { Button } from "../../components/Button";
import { EmptyState } from "../../components/EmptyState";
import { ErrorState } from "../../components/ErrorState";
import { formatDateTime } from "../../components/format";
import { LoadingState } from "../../components/LoadingState";
import { StatusBadge } from "../../components/StatusBadge";
import { cardClass, linkClass } from "../../components/ui";
import { queryKeys } from "../../hooks/queryKeys";

export function EventListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedPage = Number(searchParams.get("page") ?? "0");
  const page = Number.isInteger(requestedPage) && requestedPage > 0 ? requestedPage : 0;

  const query = useQuery({
    queryKey: queryKeys.events(page),
    queryFn: () => listEvents(page),
  });

  return (
    <div className="space-y-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-bold text-slate-900">Events</h1>
        <p className="text-sm text-slate-600">Only published events are on sale.</p>
      </header>

      {query.isPending ? <LoadingState label="Loading events…" /> : null}

      {query.isError ? (
        <ErrorState
          error={query.error}
          title="The catalog could not be loaded"
          onRetry={() => void query.refetch()}
        />
      ) : null}

      {query.isSuccess && query.data.content.length === 0 ? (
        <EmptyState title="No published events yet">
          <p>Events appear here once the catalog publishes them.</p>
        </EmptyState>
      ) : null}

      {query.isSuccess && query.data.content.length > 0 ? (
        <ul className="space-y-3">
          {query.data.content.map((event) => (
            <li key={event.id} className={cardClass}>
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <h2 className="text-lg font-semibold text-slate-900">
                    <Link to={`/events/${event.id}`} className={linkClass}>
                      {event.name}
                    </Link>
                  </h2>
                  <p className="mt-1 text-sm text-slate-600">
                    {event.venueName} · {formatDateTime(event.startsAt)}
                  </p>
                </div>
                <StatusBadge tone={event.status === "PUBLISHED" ? "positive" : "neutral"}>
                  {event.status === "PUBLISHED" ? "On sale" : "Draft"}
                </StatusBadge>
              </div>
            </li>
          ))}
        </ul>
      ) : null}

      {query.isSuccess && query.data.totalPages > 1 ? (
        <nav aria-label="Pagination" className="flex flex-wrap items-center justify-between gap-3">
          <Button
            variant="secondary"
            disabled={page === 0}
            onClick={() => setSearchParams(page <= 1 ? {} : { page: String(page - 1) })}
          >
            Previous page
          </Button>
          <p className="text-sm text-slate-600">
            Page {query.data.page + 1} of {query.data.totalPages} · {query.data.totalElements} events
          </p>
          <Button
            variant="secondary"
            disabled={query.data.page + 1 >= query.data.totalPages}
            onClick={() => setSearchParams({ page: String(page + 1) })}
          >
            Next page
          </Button>
        </nav>
      ) : null}
    </div>
  );
}
