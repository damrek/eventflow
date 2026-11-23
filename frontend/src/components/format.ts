/**
 * Prices arrive as JSON numbers in euros. A fixed format keeps the UI and the tests identical
 * regardless of the browser locale.
 */
export function formatPrice(amount: number): string {
  return `€${amount.toFixed(2)}`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeStyle: "short" }).format(date);
}

/** `MM:SS` for a remaining duration; minutes are not capped at 59. */
export function formatDuration(milliseconds: number): string {
  const safe = Number.isFinite(milliseconds) ? milliseconds : 0;
  const totalSeconds = Math.max(0, Math.ceil(safe / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}
