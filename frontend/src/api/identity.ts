/**
 * The demo identity. It is a browser-local identifier sent as `X-User-Id`; it is not
 * authentication and the UI must never present it as such.
 */

export const USER_ID_STORAGE_KEY = "eventflow.userId";

function fallbackUuid(): string {
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join("-");
}

/** Random UUID for idempotency keys, with a fallback for environments without `randomUUID`. */
export function randomUuid(): string {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function") {
    return cryptoApi.randomUUID();
  }
  return fallbackUuid();
}

export function readUserId(): string {
  const stored = window.localStorage.getItem(USER_ID_STORAGE_KEY);
  if (stored) return stored;
  const userId = `web-${randomUuid()}`;
  window.localStorage.setItem(USER_ID_STORAGE_KEY, userId);
  return userId;
}
