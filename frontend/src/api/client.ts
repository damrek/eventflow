/**
 * Single HTTP entry point. Every response goes through the same RFC 9457 problem parser so a
 * screen can render the backend `code` and `detail` without inventing a state of its own.
 */

export interface ProblemDetails {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  traceId?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly detail: string;
  readonly traceId: string | null;

  constructor(status: number, code: string, detail: string, traceId: string | null = null) {
    super(detail);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.traceId = traceId;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function toApiError(error: unknown): ApiError {
  if (isApiError(error)) return error;
  return new ApiError(
    0,
    "UNEXPECTED_ERROR",
    error instanceof Error ? error.message : "Unexpected error",
    null,
  );
}

export interface ApiRequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  userId?: string | null;
  idempotencyKey?: string | null;
  admissionToken?: string | null;
  signal?: AbortSignal;
}

async function parseProblem(response: Response): Promise<ApiError> {
  const fallback = new ApiError(
    response.status,
    `HTTP_${response.status}`,
    response.statusText || "The request failed",
    null,
  );
  try {
    const payload = (await response.json()) as ProblemDetails;
    return new ApiError(
      payload.status ?? response.status,
      payload.code ?? fallback.code,
      payload.detail ?? payload.title ?? fallback.detail,
      payload.traceId ?? null,
    );
  } catch {
    return fallback;
  }
}

export async function request<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.userId) headers["X-User-Id"] = options.userId;
  if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;
  if (options.admissionToken) headers["X-Admission-Token"] = options.admissionToken;

  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? "GET",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR", "The service could not be reached", null);
  }

  if (!response.ok) throw await parseProblem(response);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}
