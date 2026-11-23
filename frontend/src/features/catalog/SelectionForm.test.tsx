import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { LocationProbe, renderWithRouter } from "../../test/renderApp";
import type { TicketOption } from "./selectionSchema";
import { SelectionForm } from "./SelectionForm";

const options: TicketOption[] = [
  { id: "tt-general", name: "General admission", price: 50, capacity: 100, available: 3 },
  { id: "tt-vip", name: "VIP", price: 120, capacity: 10, available: 10 },
];

function renderForm() {
  return renderWithRouter(
    <>
      <SelectionForm eventId="event-1" options={options} />
      <LocationProbe />
    </>,
  );
}

async function setQuantity(label: string, value: string) {
  const input = screen.getByLabelText(label);
  await userEvent.clear(input);
  if (value !== "") await userEvent.type(input, value);
}

async function submit() {
  await userEvent.click(screen.getByRole("button", { name: "Continue to waiting room" }));
}

describe("ticket selection validation", () => {
  it("rejects a quantity above the ten-ticket limit", async () => {
    renderForm();

    await setQuantity("VIP", "11");
    await submit();

    expect(await screen.findByText("Choose at most 10 tickets per ticket type")).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/");
  });

  it("rejects a quantity above the live availability of the ticket type", async () => {
    renderForm();

    await setQuantity("General admission", "5");
    await submit();

    expect(await screen.findByText("Only 3 left for General admission")).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/");
  });

  it("requires at least one ticket type with a quantity", async () => {
    renderForm();

    await submit();

    expect(await screen.findByText("Select at least one ticket type")).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/");
  });

  it("navigates to the waiting room with one item parameter per selected type", async () => {
    renderForm();

    await setQuantity("General admission", "2");
    await setQuantity("VIP", "1");
    await submit();

    expect(await screen.findByTestId("location")).toHaveTextContent(
      "/events/event-1/waiting-room?item=tt-general:2&item=tt-vip:1",
    );
  });

  it("accepts the maximum quantity equal to the limit", async () => {
    renderForm();

    await setQuantity("VIP", "10");
    await submit();

    expect(await screen.findByTestId("location")).toHaveTextContent(
      "/events/event-1/waiting-room?item=tt-vip:10",
    );
  });

  it("refuses a blank quantity", async () => {
    renderForm();

    await setQuantity("General admission", "");
    await submit();

    expect(await screen.findByText("Enter a whole number")).toBeInTheDocument();
  });
});
