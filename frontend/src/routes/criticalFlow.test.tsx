import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import type { ReservationStatus } from "../api/booking";
import { installFakeBackend } from "../test/fakeBackend";
import { LocationProbe, renderWithRouter } from "../test/renderApp";
import { AppRoutes } from "./AppRoutes";

const EVENT_ID = "event-1";

const eventSummary = {
  id: EVENT_ID,
  name: "Green Day Live",
  venueId: "venue-1",
  venueName: "Madrid Arena",
  startsAt: "2027-03-01T20:00:00Z",
  status: "PUBLISHED",
};

const eventDetail = {
  ...eventSummary,
  totalCapacity: 100,
  ticketTypes: [{ id: "tt-general", name: "General admission", price: 50, capacity: 100 }],
};

const availability = {
  eventId: EVENT_ID,
  ticketTypes: [
    { ticketTypeId: "tt-general", price: 50, capacity: 100, available: 10, held: 0, sold: 0 },
  ],
};

function waitingRoomBody(status: "WAITING" | "ADMITTED", token: string | null) {
  return {
    eventId: EVENT_ID,
    userId: "web-flow",
    status,
    position: status === "WAITING" ? 1 : null,
    waitingCount: 1,
    token,
    expiresAt: token ? new Date(Date.now() + 120_000).toISOString() : null,
  };
}

function reservationBody(status: ReservationStatus) {
  return {
    reservationId: "res-1",
    eventId: EVENT_ID,
    userId: "web-flow",
    status,
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
    createdAt: new Date().toISOString(),
    items: [{ ticketTypeId: "tt-general", quantity: 2, unitPrice: 50 }],
    totalAmount: 100,
  };
}

describe("critical flow", () => {
  it("walks catalog → detail → selection → waiting room → reserve → payment → confirmed", async () => {
    let statusCalls = 0;
    let checkoutCalls = 0;
    let reservationGets = 0;

    const backend = installFakeBackend([
      {
        method: "GET",
        path: "/api/catalog/events",
        handler: () => ({
          body: { content: [eventSummary], page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      },
      {
        method: "GET",
        path: `/api/catalog/events/${EVENT_ID}`,
        handler: () => ({ body: eventDetail }),
      },
      {
        method: "GET",
        path: `/api/booking/events/${EVENT_ID}/availability`,
        handler: () => ({ body: availability }),
      },
      {
        method: "POST",
        path: `/api/booking/waiting-room/${EVENT_ID}/join`,
        handler: () => ({ body: waitingRoomBody("WAITING", null) }),
      },
      {
        method: "GET",
        path: `/api/booking/waiting-room/${EVENT_ID}/status`,
        handler: () => {
          statusCalls += 1;
          if (statusCalls < 2) return { body: waitingRoomBody("WAITING", null) };
          return { body: waitingRoomBody("ADMITTED", "flow-token") };
        },
      },
      {
        method: "POST",
        path: "/api/booking/reservations",
        handler: () => ({ status: 201, body: reservationBody("HELD") }),
      },
      {
        method: "POST",
        path: "/api/booking/reservations/res-1/checkout",
        handler: () => {
          checkoutCalls += 1;
          return { body: reservationBody("PENDING_PAYMENT") };
        },
      },
      {
        method: "GET",
        path: /^\/api\/booking\/reservations\/res-1$/,
        handler: () => {
          if (checkoutCalls === 0) return { body: reservationBody("HELD") };
          reservationGets += 1;
          return { body: reservationBody(reservationGets === 1 ? "PENDING_PAYMENT" : "CONFIRMED") };
        },
      },
      {
        method: "GET",
        path: "/api/notification/notifications",
        handler: () => ({
          body: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      },
    ]);

    renderWithRouter(
      <>
        <AppRoutes />
        <LocationProbe />
      </>,
    );

    // Catalog
    expect(await screen.findByRole("heading", { name: "Events", level: 1 })).toBeInTheDocument();
    await userEvent.click(await screen.findByRole("link", { name: "Green Day Live" }));

    // Detail + live availability
    expect(
      await screen.findByRole("heading", { name: "Green Day Live", level: 1 }),
    ).toBeInTheDocument();
    const availabilityCells = await screen.findAllByRole("cell", { name: "10" });
    expect(availabilityCells.length).toBeGreaterThan(0);

    // Selection
    const quantityInput = screen.getByLabelText("General admission");
    await userEvent.clear(quantityInput);
    await userEvent.type(quantityInput, "2");
    await userEvent.click(screen.getByRole("button", { name: "Continue to waiting room" }));

    // Waiting room: joins, then is admitted on a later poll
    expect(await screen.findByText("You are in the queue")).toBeInTheDocument();

    // Reserve page
    expect(await screen.findByText("€100.00", {}, { timeout: 15_000 })).toBeInTheDocument();
    const createCall = backend.callsTo("POST", "/api/booking/reservations")[0];
    expect(createCall.headers["X-Admission-Token"]).toBe("flow-token");

    // Payment
    await userEvent.click(screen.getByRole("button", { name: "Pay now" }));

    expect(
      await screen.findByText("Payment confirmed", {}, { timeout: 15_000 }),
    ).toBeInTheDocument();

    await waitFor(() =>
      expect(screen.getByTestId("location")).toHaveTextContent(
        "/events/event-1/reserve?item=tt-general:2",
      ),
    );

    const userScopedCalls = backend.calls.filter(
      (call) => call.path.includes("/waiting-room/") || call.path.includes("/reservations"),
    );
    expect(userScopedCalls.length).toBeGreaterThan(2);
    // The confirmation was read from the backend after checkout, never decided by the client.
    expect(reservationGets).toBeGreaterThan(0);
    expect(
      userScopedCalls.every((call) => String(call.headers["X-User-Id"] ?? "").startsWith("web-")),
    ).toBe(true);
  }, 45_000);
});
