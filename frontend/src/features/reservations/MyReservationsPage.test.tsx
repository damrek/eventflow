import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";

import { RESERVATION_IDS_STORAGE_KEY } from "../../hooks/useReservationIds";
import { installFakeBackend, pageOf } from "../../test/fakeBackend";
import { renderWithRouter } from "../../test/renderApp";
import { MyReservationsPage } from "./MyReservationsPage";

function reservationBody(status: "HELD" | "CONFIRMED") {
  return {
    reservationId: "res-1",
    eventId: "event-1",
    userId: "web-test",
    status,
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
    createdAt: new Date().toISOString(),
    items: [{ ticketTypeId: "tt-general", quantity: 2, unitPrice: 50 }],
    totalAmount: 100,
  };
}

describe("my reservations", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("renders the backend status of the stored reservations and the notification mailbox", async () => {
    window.localStorage.setItem(RESERVATION_IDS_STORAGE_KEY, JSON.stringify(["res-1"]));

    const backend = installFakeBackend([
      {
        method: "GET",
        path: "/api/booking/reservations/res-1",
        handler: () => ({ body: reservationBody("CONFIRMED") }),
      },
      {
        method: "GET",
        path: "/api/notification/notifications",
        handler: () => ({
          body: pageOf([
            {
              id: "notif-1",
              userId: "web-test",
              type: "BOOKING_CONFIRMED",
              content: "Your booking is confirmed",
              status: "UNREAD",
              createdAt: new Date().toISOString(),
            },
          ]),
        }),
      },
    ]);

    renderWithRouter(<MyReservationsPage />);

    expect(await screen.findByText("Confirmed")).toBeInTheDocument();
    expect(await screen.findByText("Your booking is confirmed")).toBeInTheDocument();
    expect(screen.getByText("Unread")).toBeInTheDocument();

    const mailboxCall = backend.callsTo("GET", "/api/notification/notifications")[0];
    expect(mailboxCall.search.get("userId")).toMatch(/^web-/);
  });

  it("shows an empty state when the browser holds no reservation id", async () => {
    installFakeBackend([
      {
        method: "GET",
        path: "/api/notification/notifications",
        handler: () => ({ body: pageOf([]) }),
      },
    ]);

    renderWithRouter(<MyReservationsPage />);

    expect(await screen.findByText("No reservations in this browser yet")).toBeInTheDocument();
    expect(await screen.findByText("No notifications yet")).toBeInTheDocument();
  });
});
