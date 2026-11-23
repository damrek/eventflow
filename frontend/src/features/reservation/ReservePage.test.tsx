import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { Route, Routes } from "react-router";

import type { ReservationStatus } from "../../api/booking";
import { installFakeBackend } from "../../test/fakeBackend";
import { LocationProbe, renderWithRouter } from "../../test/renderApp";
import { ReservePage } from "./ReservePage";

const EVENT_ID = "event-1";
const RESERVE_ROUTE = "/events/event-1/reserve?item=tt-general:2";
const WAITING_ROOM_ROUTE = "/events/event-1/waiting-room?item=tt-general:2";

const eventDetail = {
  id: EVENT_ID,
  name: "Green Day Live",
  venueId: "venue-1",
  venueName: "Madrid Arena",
  startsAt: "2027-03-01T20:00:00Z",
  status: "PUBLISHED",
  totalCapacity: 100,
  ticketTypes: [{ id: "tt-general", name: "General admission", price: 50, capacity: 100 }],
};

function admitted() {
  return {
    body: {
      eventId: EVENT_ID,
      userId: "web-test",
      status: "ADMITTED",
      position: null,
      waitingCount: 0,
      token: "admission-token-1",
      expiresAt: new Date(Date.now() + 120_000).toISOString(),
    },
  };
}

function reservationBody(reservationId: string, status: ReservationStatus) {
  return {
    reservationId,
    eventId: EVENT_ID,
    userId: "web-test",
    status,
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
    createdAt: new Date().toISOString(),
    items: [{ ticketTypeId: "tt-general", quantity: 2, unitPrice: 50 }],
    totalAmount: 100,
  };
}

function renderReserve() {
  return renderWithRouter(
    <Routes>
      <Route path="/events/:eventId/reserve" element={<ReservePage />} />
      <Route path="/events/:eventId/waiting-room" element={<LocationProbe />} />
      <Route path="/events/:eventId" element={<LocationProbe />} />
    </Routes>,
    { route: RESERVE_ROUTE },
  );
}

describe("reserve page", () => {
  it("creates the hold once and renders the backend status of a confirmed payment", async () => {
    let checkoutCalls = 0;
    let reservationGets = 0;
    let createCalls = 0;

    const backend = installFakeBackend([
      { method: "GET", path: `/api/booking/waiting-room/${EVENT_ID}/status`, handler: admitted },
      { method: "GET", path: `/api/catalog/events/${EVENT_ID}`, handler: () => ({ body: eventDetail }) },
      {
        method: "POST",
        path: "/api/booking/reservations",
        handler: () => {
          createCalls += 1;
          return { status: 201, body: reservationBody("res-1", "HELD") };
        },
      },
      {
        method: "POST",
        path: "/api/booking/reservations/res-1/checkout",
        handler: () => {
          checkoutCalls += 1;
          return { body: reservationBody("res-1", "PENDING_PAYMENT") };
        },
      },
      {
        method: "GET",
        path: /^\/api\/booking\/reservations\/res-\d+$/,
        handler: () => {
          if (checkoutCalls === 0) return { body: reservationBody("res-1", "HELD") };
          reservationGets += 1;
          return {
            body: reservationBody("res-1", reservationGets === 1 ? "PENDING_PAYMENT" : "CONFIRMED"),
          };
        },
      },
    ]);

    renderReserve();

    expect(await screen.findByText("€100.00")).toBeInTheDocument();
    expect(screen.getByText("Time remaining:")).toBeInTheDocument();

    const createCall = backend.callsTo("POST", "/api/booking/reservations")[0];
    expect(createCalls).toBe(1);
    expect(createCall.headers["X-Admission-Token"]).toBe("admission-token-1");
    expect(createCall.headers["Idempotency-Key"]).toBeTruthy();
    expect(createCall.headers["X-User-Id"]).toMatch(/^web-/);
    expect(createCall.body).toEqual({
      eventId: EVENT_ID,
      items: [{ ticketTypeId: "tt-general", quantity: 2 }],
    });

    await userEvent.click(screen.getByRole("button", { name: "Pay now" }));

    expect(
      await screen.findByText("Payment pending — waiting for the payment service…"),
    ).toBeInTheDocument();
    expect(
      await screen.findByText("Payment confirmed", {}, { timeout: 12_000 }),
    ).toBeInTheDocument();

    expect(backend.callsTo("POST", "/api/booking/reservations")).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "Pay now" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reserve again" })).toBeInTheDocument();
  }, 25_000);

  it("renders a rejected payment and mints a new idempotency key on retry", async () => {
    let createCalls = 0;
    let checkoutCalls = 0;

    const backend = installFakeBackend([
      { method: "GET", path: `/api/booking/waiting-room/${EVENT_ID}/status`, handler: admitted },
      { method: "GET", path: `/api/catalog/events/${EVENT_ID}`, handler: () => ({ body: eventDetail }) },
      {
        method: "POST",
        path: "/api/booking/reservations",
        handler: () => {
          createCalls += 1;
          return {
            status: 201,
            body: reservationBody(createCalls === 1 ? "res-1" : "res-2", "HELD"),
          };
        },
      },
      {
        method: "POST",
        path: "/api/booking/reservations/res-1/checkout",
        handler: () => {
          checkoutCalls += 1;
          return { body: reservationBody("res-1", "PENDING_PAYMENT") };
        },
      },
      {
        method: "GET",
        path: /^\/api\/booking\/reservations\/res-\d+$/,
        handler: (request) => {
          if (request.path.endsWith("res-2")) {
            return { body: reservationBody("res-2", "HELD") };
          }
          return {
            body: reservationBody(
              "res-1",
              checkoutCalls === 0 ? "HELD" : "PAYMENT_FAILED",
            ),
          };
        },
      },
    ]);

    renderReserve();

    expect(await screen.findByText("€100.00")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Pay now" }));

    expect(
      await screen.findByText("The payment was rejected by the payment provider", {}, { timeout: 12_000 }),
    ).toBeInTheDocument();

    const retryButton = screen.getByRole("button", { name: "Reserve again" });
    await userEvent.click(retryButton);

    await waitFor(() => expect(createCalls).toBe(2));
    const keys = backend
      .callsTo("POST", "/api/booking/reservations")
      .map((call) => call.headers["Idempotency-Key"]);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBeTruthy();
    expect(keys[0]).not.toBe(keys[1]);
  }, 25_000);

  it("renders an expired hold reported by the backend", async () => {
    installFakeBackend([
      { method: "GET", path: `/api/booking/waiting-room/${EVENT_ID}/status`, handler: admitted },
      { method: "GET", path: `/api/catalog/events/${EVENT_ID}`, handler: () => ({ body: eventDetail }) },
      {
        method: "POST",
        path: "/api/booking/reservations",
        handler: () => ({ status: 201, body: reservationBody("res-1", "HELD") }),
      },
      {
        method: "GET",
        path: /^\/api\/booking\/reservations\/res-\d+$/,
        handler: () => ({ body: reservationBody("res-1", "EXPIRED") }),
      },
    ]);

    renderReserve();

    expect(
      await screen.findByText("The hold expired before the payment completed", {}, { timeout: 8000 }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Pay now" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reserve again" })).toBeInTheDocument();
  }, 20_000);

  it("replays a creation error with the same idempotency key", async () => {
    let createCalls = 0;

    const backend = installFakeBackend([
      { method: "GET", path: `/api/booking/waiting-room/${EVENT_ID}/status`, handler: admitted },
      { method: "GET", path: `/api/catalog/events/${EVENT_ID}`, handler: () => ({ body: eventDetail }) },
      {
        method: "POST",
        path: "/api/booking/reservations",
        handler: () => {
          createCalls += 1;
          if (createCalls === 1) {
            return {
              status: 503,
              body: {
                status: 503,
                code: "SERVICE_UNAVAILABLE",
                detail: "The booking service is temporarily unavailable",
                traceId: "trace-create",
              },
            };
          }
          return { status: 201, body: reservationBody("res-1", "HELD") };
        },
      },
      {
        method: "GET",
        path: /^\/api\/booking\/reservations\/res-\d+$/,
        handler: () => ({ body: reservationBody("res-1", "HELD") }),
      },
    ]);

    renderReserve();

    expect(await screen.findByText("The reservation could not be created")).toBeInTheDocument();
    expect(screen.getByText("The booking service is temporarily unavailable")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByText("€100.00")).toBeInTheDocument();
    const keys = backend
      .callsTo("POST", "/api/booking/reservations")
      .map((call) => call.headers["Idempotency-Key"]);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
  }, 20_000);

  it("returns to the waiting room when the user is not admitted", async () => {
    const backend = installFakeBackend([
      {
        method: "GET",
        path: `/api/booking/waiting-room/${EVENT_ID}/status`,
        handler: () => ({
          body: {
            eventId: EVENT_ID,
            userId: "web-test",
            status: "WAITING",
            position: 3,
            waitingCount: 8,
            token: null,
            expiresAt: null,
          },
        }),
      },
    ]);

    renderReserve();

    await waitFor(
      () => expect(screen.getByTestId("location")).toHaveTextContent(WAITING_ROOM_ROUTE),
      { timeout: 5000 },
    );
    expect(backend.callsTo("POST", "/api/booking/reservations")).toHaveLength(0);
  }, 15_000);

  it("creates the reservation directly when the waiting room is down", async () => {
    installFakeBackend([
      {
        method: "GET",
        path: `/api/booking/waiting-room/${EVENT_ID}/status`,
        handler: () => ({ status: 503, body: { status: 503, code: "WAITING_ROOM_UNAVAILABLE", detail: "Redis is down", traceId: "trace-503" } }),
      },
      { method: "GET", path: `/api/catalog/events/${EVENT_ID}`, handler: () => ({ body: eventDetail }) },
      {
        method: "POST",
        path: "/api/booking/reservations",
        handler: () => ({ status: 201, body: reservationBody("res-1", "HELD") }),
      },
      {
        method: "GET",
        path: /^\/api\/booking\/reservations\/res-\d+$/,
        handler: () => ({ body: reservationBody("res-1", "HELD") }),
      },
    ]);

    renderReserve();

    expect(await screen.findByText("€100.00")).toBeInTheDocument();
    expect(screen.getByText("The waiting room is unavailable")).toBeInTheDocument();
  }, 15000);
});
