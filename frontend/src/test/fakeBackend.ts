import { vi } from "vitest";

/**
 * A small route-table backend for `fetch`. Tests describe the API they expect, so a screen that
 * calls an endpoint nobody declared fails loudly instead of passing on a silent 404 body.
 */
export interface FakeRequest {
  method: string;
  path: string;
  search: URLSearchParams;
  headers: Record<string, string>;
  body: unknown;
}

export interface FakeResponse {
  status?: number;
  body?: unknown;
}

export interface FakeRoute {
  method: "GET" | "POST";
  path: string | RegExp;
  handler: (request: FakeRequest) => FakeResponse | Promise<FakeResponse>;
}

function matches(pattern: string | RegExp, path: string): boolean {
  return typeof pattern === "string" ? pattern === path : pattern.test(path);
}

function headersToObject(headers: HeadersInit | undefined): Record<string, string> {
  if (!headers) return {};
  if (headers instanceof Headers) return Object.fromEntries(headers.entries());
  if (Array.isArray(headers)) return Object.fromEntries(headers);
  return { ...headers };
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function installFakeBackend(routes: FakeRoute[]) {
  const calls: FakeRequest[] = [];

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url =
      typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    const parsed = new URL(url, "http://eventflow.test");
    const method = (init?.method ?? "GET").toUpperCase();
    const request: FakeRequest = {
      method,
      path: parsed.pathname,
      search: parsed.searchParams,
      headers: headersToObject(init?.headers),
      body: typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined,
    };
    calls.push(request);

    const route = routes.find(
      (candidate) => candidate.method === method && matches(candidate.path, request.path),
    );
    if (!route) {
      return jsonResponse(404, {
        type: "about:blank",
        title: "NOT_FOUND",
        status: 404,
        detail: `No fake route for ${method} ${request.path}`,
        code: "NOT_FOUND",
        traceId: "trace-unrouted",
      });
    }

    const result = await route.handler(request);
    return jsonResponse(result.status ?? 200, result.body);
  });

  vi.stubGlobal("fetch", fetchMock);

  return {
    calls,
    fetchMock,
    callsTo(method: string, path: string | RegExp): FakeRequest[] {
      return calls.filter((call) => call.method === method && matches(path, call.path));
    },
  };
}

/** RFC 9457 problem body as the services emit it. */
export function problem(status: number, code: string, detail: string, traceId = "trace-test") {
  return {
    status,
    body: { type: "about:blank", title: code, status, detail, instance: "/", code, traceId },
  };
}

export function pageOf<T>(content: T[], page = 0, size = 20) {
  return {
    content,
    page,
    size,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
  };
}
