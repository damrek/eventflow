import { z } from "zod";

import { MAX_QUANTITY } from "../reservation/items";

export interface TicketOption {
  id: string;
  name: string;
  price: number;
  capacity: number;
  available: number;
}

export type SelectionValues = Record<string, number>;

export function quantityFieldName(ticketTypeId: string): string {
  return `quantity-${ticketTypeId}`;
}

function quantityMessage(option: TicketOption, max: number): string {
  if (max === 0) return `${option.name} is sold out`;
  if (max < MAX_QUANTITY) return `Only ${max} left for ${option.name}`;
  return `Choose at most ${MAX_QUANTITY} tickets per ticket type`;
}

export function selectionDefaults(options: TicketOption[]): SelectionValues {
  const values: SelectionValues = {};
  for (const option of options) values[quantityFieldName(option.id)] = 0;
  return values;
}

/**
 * Zod 4 note: quantities are numbers (the inputs register with `valueAsNumber`); coercion breaks
 * the React Hook Form resolver types, so the schema stays `z.number()`. The per-type maximum is
 * the smaller of the ticket limit and the live `available` the backend just reported.
 */
export function buildSelectionSchema(options: TicketOption[]) {
  const shape: Record<string, z.ZodNumber> = {};
  for (const option of options) {
    const max = Math.min(MAX_QUANTITY, option.available);
    shape[quantityFieldName(option.id)] = z
      .number({ error: "Enter a whole number" })
      .int("Enter a whole number")
      .min(0, "Quantity cannot be negative")
      .max(max, quantityMessage(option, max));
  }

  // The "at least one" rule is attached to the first real field: the form has no field of its
  // own, and a root issue is not addressable through React Hook Form's error map.
  const anchor = options.length > 0 ? quantityFieldName(options[0].id) : "quantity";

  return z
    .object(shape)
    .refine((values) => Object.values(values).some((quantity) => quantity > 0), {
      message: "Select at least one ticket type",
      path: [anchor],
    });
}
