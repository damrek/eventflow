/**
 * Selected ticket types travel from the detail page to the waiting room and the reserve page as
 * repeated `item=<ticketTypeId>:<quantity>` query parameters.
 */
export interface SelectedItem {
  ticketTypeId: string;
  quantity: number;
}

export const MAX_QUANTITY = 10;

/** Malformed or out-of-range entries are dropped: the form is the only producer. */
export function parseSelectedItems(params: URLSearchParams): SelectedItem[] {
  const items: SelectedItem[] = [];
  for (const entry of params.getAll("item")) {
    const separator = entry.lastIndexOf(":");
    if (separator <= 0) continue;
    const ticketTypeId = entry.slice(0, separator);
    const quantity = Number(entry.slice(separator + 1));
    if (ticketTypeId === "") continue;
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > MAX_QUANTITY) continue;
    items.push({ ticketTypeId, quantity });
  }
  return items;
}

/**
 * Returns the query string without the leading `?`. The value keeps a literal colon, as the
 * backend contract describes it (`item=<ticketTypeId>:<quantity>`); the id itself is encoded.
 */
export function selectedItemsSearch(items: SelectedItem[]): string {
  return items
    .map((item) => `item=${encodeURIComponent(item.ticketTypeId)}:${item.quantity}`)
    .join("&");
}

export function selectedItemsKey(items: SelectedItem[]): string {
  return items.map((item) => `${item.ticketTypeId}:${item.quantity}`).join("|");
}
