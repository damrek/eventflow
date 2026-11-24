import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { Route, Routes } from "react-router";

import { installFakeBackend, problem } from "../../test/fakeBackend";
import { LocationProbe, renderWithRouter } from "../../test/renderApp";
import { WaitingRoomPage } from "./WaitingRoomPage";

const JOIN_PATH = "/api/booking/waiting-room/event-1/join";
const STATUS_PATH = "/api/booking/waiting-room/event-1/status";
const RESERVE_ROUTE = "/events/event-1/reserve?item=tt-general:2";

function waitingRoomBody(
  status: "WAITING" | "ADMITTED" | "NOT_JOINED",
  position: number | null,
  token: string | null = null,
) {
  return {
    eventId: "event-1",
    userId: "web-test",
    status,
    position,
    waitingCount: 5,
    token,
    expiresAt: token ? new Date(Date.now() + 120_000).toISOString() : null,
  };
}

function renderWaitingRoom() {
  return renderWithRouter(
    <Routes>
      <Route path="/events/:eventId/waiting-room" element={<WaitingRoomPage />} />
      <Route path="/events/:eventId/reserve" element={<LocationProbe />} />
    </Routes>,
    { route: "/events/event-1/waiting-room?item=tt-general:2" },
  );
}

describe("waiting room", () => {
  it("joins, shows the position, then navigates to the reservation when admitted", async () => {
    let statusCalls = 0;
    const backend = installFakeBackend([
      { method: "POST", path: JOIN_PATH, handler: () => ({ body: waitingRoomBody("WAITING", 2) }) },
      {
        method: "GET",
        path: STATUS_PATH,
        handler: () => {
          statusCalls += 1;
          return {
            body:
              statusCalls === 1
                ? waitingRoomBody("WAITING", 2)
                : waitingRoomBody("ADMITTED", null, "token-1"),
          };
        },
      },
    ]);

    renderWaitingRoom();

    expect(await screen.findByText(/Position 2 · 5 waiting/)).toBeInTheDocument();
    expect(statusCalls).toBe(1);

    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(RESERVE_ROUTE), {
      timeout: 8000,
    });

    const joinCalls = backend.callsTo("POST", JOIN_PATH);
    expect(joinCalls).toHaveLength(1);
    expect(joinCalls[0].headers["X-User-Id"]).toMatch(/^web-/);
  }, 20_000);

  it("keeps polling the position while the user waits", async () => {
    let statusCalls = 0;
    installFakeBackend([
      { method: "POST", path: JOIN_PATH, handler: () => ({ body: waitingRoomBody("WAITING", 4) }) },
      {
        method: "GET",
        path: STATUS_PATH,
        handler: () => {
          statusCalls += 1;
          return { body: waitingRoomBody("WAITING", Math.max(1, 4 - statusCalls)) };
        },
      },
    ]);

    renderWaitingRoom();

    expect(await screen.findByText(/Position 4 · 5 waiting/)).toBeInTheDocument();
    expect(
      await screen.findByText(/Position 3 · 5 waiting/, {}, { timeout: 6000 }),
    ).toBeInTheDocument();
  }, 20_000);

  it("offers the direct reservation path when the waiting room answers 503", async () => {
    installFakeBackend([
      {
        method: "POST",
        path: JOIN_PATH,
        handler: () => problem(503, "WAITING_ROOM_UNAVAILABLE", "The waiting room is unavailable"),
      },
    ]);

    renderWaitingRoom();

    expect(await screen.findByText("The waiting room is unavailable")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "Continue without waiting" });
    expect(link).toHaveAttribute("href", RESERVE_ROUTE);

    await userEvent.click(link);

    expect(screen.getByTestId("location")).toHaveTextContent(RESERVE_ROUTE);
  });

  it("reports a failed join with the backend problem detail", async () => {
    installFakeBackend([
      {
        method: "POST",
        path: JOIN_PATH,
        handler: () => problem(429, "RATE_LIMITED", "Too many join attempts"),
      },
    ]);

    renderWaitingRoom();

    expect(await screen.findByText("Too many join attempts")).toBeInTheDocument();
    expect(screen.getByText(/Code: RATE_LIMITED/)).toBeInTheDocument();
    expect(screen.getByText(/Trace: trace-test/)).toBeInTheDocument();
  });
});
