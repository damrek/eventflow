import type { ReactNode } from "react";

import { toApiError } from "../api/client";
import { Button } from "./Button";

export interface ErrorStateProps {
  error: unknown;
  title?: string;
  onRetry?: () => void;
  retryLabel?: string;
  children?: ReactNode;
}

/**
 * Renders the backend problem verbatim: `detail` and `code` are the service's, never invented,
 * and the `traceId` is the one the logs carry.
 */
export function ErrorState({
  error,
  title = "Something went wrong",
  onRetry,
  retryLabel = "Try again",
  children,
}: ErrorStateProps) {
  const apiError = toApiError(error);
  return (
    <div
      role="alert"
      className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-900"
    >
      <p className="font-semibold">{title}</p>
      <p className="mt-1">{apiError.detail}</p>
      <p className="mt-1 text-xs text-rose-700">
        Code: {apiError.code}
        {apiError.traceId ? ` · Trace: ${apiError.traceId}` : ""}
      </p>
      {onRetry ? (
        <div className="mt-3">
          <Button variant="secondary" onClick={onRetry}>
            {retryLabel}
          </Button>
        </div>
      ) : null}
      {children}
    </div>
  );
}
