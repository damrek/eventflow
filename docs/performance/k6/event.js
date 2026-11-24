import http from "k6/http";
import { fail, sleep } from "k6";

/**
 * Seeds one published event through the front door, the same path the SPA uses:
 * `POST /api/catalog/venues` -> `POST /api/catalog/events` -> `POST /api/catalog/events/{id}/publish`.
 * Booking provisions the inventory from `EventPublished` (ADR 0009), so the caller must wait until
 * the availability endpoint answers with the expected capacity before sending load at it.
 */
const JSON_HEADERS = { headers: { "Content-Type": "application/json" } };

export function seedPublishedEvent(baseUrl, { name, capacity }) {
  const venue = http.post(
    `${baseUrl}/api/catalog/venues`,
    JSON.stringify({ name: `${name} venue`, address: "Calle 1, Madrid", capacity }),
    JSON_HEADERS,
  );
  if (!isSuccess(venue.status)) {
    fail(`venue creation answered ${venue.status}: ${venue.body}`);
  }

  const startsAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
  const event = http.post(
    `${baseUrl}/api/catalog/events`,
    JSON.stringify({
      name,
      venueId: venue.json("id"),
      startsAt,
      ticketTypes: [{ name: "General", price: 25.0, capacity }],
    }),
    JSON_HEADERS,
  );
  if (!isSuccess(event.status)) {
    fail(`event creation answered ${event.status}: ${event.body}`);
  }

  const eventId = event.json("id");
  const published = http.post(`${baseUrl}/api/catalog/events/${eventId}/publish`);
  if (!isSuccess(published.status)) {
    fail(`publish answered ${published.status}: ${published.body}`);
  }

  return { eventId, capacity };
}

export function availability(baseUrl, eventId) {
  const type = tryAvailability(baseUrl, eventId);
  if (type === null) {
    fail(`availability for event ${eventId} is not provisioned yet`);
  }
  return type;
}

/** `null` while Booking has not consumed `EventPublished` yet (the endpoint answers 404). */
export function tryAvailability(baseUrl, eventId) {
  // 404 is the expected answer until Booking consumes `EventPublished`, so it is not a failed request.
  const response = http.get(`${baseUrl}/api/booking/events/${eventId}/availability`, {
    responseCallback: http.expectedStatuses(200, 404),
  });
  if (response.status !== 200) {
    return null;
  }
  const ticketTypes = response.json("ticketTypes");
  if (!Array.isArray(ticketTypes) || ticketTypes.length === 0) {
    return null;
  }
  return ticketTypes[0];
}

/** The broker hop is asynchronous: without this wait the first VUs would hit an unprovisioned event. */
export function waitForInventory(baseUrl, eventId, capacity, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const type = tryAvailability(baseUrl, eventId);
    if (type !== null && type.capacity === capacity && type.available === capacity) {
      return type;
    }
    if (Date.now() > deadline) {
      fail(
        `inventory was not provisioned within ${timeoutMs} ms (last reading: ${JSON.stringify(type)})`,
      );
    }
    sleep(0.5);
  }
}

function isSuccess(status) {
  return status >= 200 && status < 300;
}
