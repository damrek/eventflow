import http from "k6/http";
import { Counter, Gauge } from "k6/metrics";

import { availability, seedPublishedEvent, waitForInventory } from "./event.js";
import { summaryText } from "./summary.js";

/**
 * Contention on a small stock: every iteration tries to hold one unit of a 50-unit event, with its
 * own `Idempotency-Key` and its own user (the rate limiter is per user). `201` and a `422` refusal
 * are both correct answers — the invariant is that the sum of them is exactly the capacity, never
 * more, and that `available + held + sold = capacity` still holds afterwards.
 *
 * `holds_created == CAPACITY`, `holds_refused == attempts - CAPACITY` and `holds_other == 0` are
 * thresholds, so an oversell, a lost unit or an unexpected answer fails the run instead of hiding in
 * a log line; the closing availability is written as gauges so it lands in the result object.
 */
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const CAPACITY = Number(__ENV.CAPACITY || 50);
const VUS = Number(__ENV.VUS || 40);
const ITERATIONS = Number(__ENV.ITERATIONS || 5);

const created = new Counter("holds_created");
const refused = new Counter("holds_refused");
const other = new Counter("holds_other");
const finalAvailable = new Gauge("final_available");
const finalHeld = new Gauge("final_held");
const finalSold = new Gauge("final_sold");

export const options = {
  scenarios: {
    contend: {
      executor: "per-vu-iterations",
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: "2m",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    [`holds_created`]: [`count==${CAPACITY}`],
    [`holds_refused`]: [`count==${VUS * ITERATIONS - CAPACITY}`],
    // Anything that is neither a hold nor a stock refusal is a defect, not a slow answer.
    holds_other: ["count==0"],
    http_req_duration: ["p(95)<300"],
  },
};

export function setup() {
  const event = seedPublishedEvent(BASE_URL, {
    name: `k6 contention ${Date.now()}`,
    capacity: CAPACITY,
  });
  waitForInventory(BASE_URL, event.eventId, event.capacity);
  const type = availability(BASE_URL, event.eventId);
  return { eventId: event.eventId, ticketTypeId: type.ticketTypeId, capacity: CAPACITY };
}

export default function (data) {
  const response = http.post(
    `${BASE_URL}/api/booking/reservations`,
    JSON.stringify({
      eventId: data.eventId,
      items: [{ ticketTypeId: data.ticketTypeId, quantity: 1 }],
    }),
    {
      headers: {
        "Content-Type": "application/json",
        "X-User-Id": `k6-user-${__VU}-${__ITER}-${Date.now()}`,
        "Idempotency-Key": `k6-${__VU}-${__ITER}-${Date.now()}`,
      },
      // A refusal for insufficient stock is a correct answer here, not a failed request.
      responseCallback: http.expectedStatuses(201, 422),
    },
  );

  if (response.status === 201) {
    created.add(1);
    return;
  }
  if (response.status === 422) {
    refused.add(1);
    return;
  }
  other.add(1);
  console.error(`unexpected answer ${response.status}: ${response.body}`);
}

export function teardown(data) {
  const type = availability(BASE_URL, data.eventId);
  finalAvailable.add(type.available);
  finalHeld.add(type.held);
  finalSold.add(type.sold);
  const sum = type.available + type.held + type.sold;
  console.log(
    `availability after the run: capacity ${type.capacity}, available ${type.available}, ` +
      `held ${type.held}, sold ${type.sold}`,
  );
  if (sum !== type.capacity) {
    throw new Error(
      `INSUFFICIENT invariant: ${type.available} + ${type.held} + ${type.sold} != ${type.capacity}`,
    );
  }
  if (type.available < 0 || type.held < 0 || type.sold < 0) {
    throw new Error(`negative counter after the run: ${JSON.stringify(type)}`);
  }
  // Every unit that was held must be the unit of a reservation this run created: a hold that went
  // missing would keep the sum balanced while quietly selling one ticket twice.
  if (type.held !== data.capacity) {
    throw new Error(`expected ${data.capacity} units held, found ${type.held}`);
  }
}

export function handleSummary(data) {
  return {
    [`/results/${__ENV.RESULT_FILE || "reservation-contention.json"}`]: JSON.stringify(data, null, 2),
    stdout: summaryText(data),
  };
}
