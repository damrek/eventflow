import http from "k6/http";
import { check } from "k6";

import { seedPublishedEvent, waitForInventory } from "./event.js";
import { summaryText } from "./summary.js";

/**
 * The read path a visitor takes before buying: the published-event list and one event's live
 * availability. It answers "how fast does the system serve the pages the SPA polls", which is what a
 * waiting room turns into a burst, and it is the scenario a capacity change is measured against.
 */
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const VUS = Number(__ENV.VUS || 20);

export const options = {
  scenarios: {
    browse: {
      executor: "ramping-arrival-rate",
      startRate: 5,
      timeUnit: "1s",
      preAllocatedVUs: 10,
      maxVUs: 50,
      stages: [
        { target: VUS, duration: "20s" },
        { target: VUS, duration: "30s" },
        { target: 0, duration: "10s" },
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<150", "p(99)<400"],
    "http_req_duration{endpoint:list-events}": ["p(95)<150"],
    "http_req_duration{endpoint:availability}": ["p(95)<150"],
  },
};

export function setup() {
  const event = seedPublishedEvent(BASE_URL, {
    name: `k6 read path ${Date.now()}`,
    capacity: 5000,
  });
  waitForInventory(BASE_URL, event.eventId, event.capacity);
  return event;
}

export default function (data) {
  if (Math.random() < 0.8) {
    const response = http.get(`${BASE_URL}/api/catalog/events?page=0&size=20`, {
      tags: { endpoint: "list-events" },
    });
    check(response, {
      "list answers 200": (r) => r.status === 200,
      "list returns content": (r) => Array.isArray(r.json("content")),
    });
    return;
  }

  const response = http.get(`${BASE_URL}/api/booking/events/${data.eventId}/availability`, {
    tags: { endpoint: "availability" },
  });
  check(response, {
    "availability answers 200": (r) => r.status === 200,
    "availability carries ticket types": (r) => Array.isArray(r.json("ticketTypes")),
  });
}

export function handleSummary(data) {
  return {
    [`/results/${__ENV.RESULT_FILE || "read-path.json"}`]: JSON.stringify(data, null, 2),
    stdout: summaryText(data),
  };
}
