#!/usr/bin/env bash
# Proves the documented flow through the front door only, exactly as a browser would do it:
#
#   SPA shell -> venue -> event -> publish -> waiting room -> hold -> checkout
#             -> CONFIRMED -> notification -> inventory totals
#
#   ./scripts/stack-verify.sh [base-url]
#
# The default base URL is http://localhost:${APP_PORT:-8080}. Nothing here talks to a service
# directly, so a broken proxy rewrite fails the run instead of hiding behind a passing API test.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -f .env ]; then
  # shellcheck disable=SC1091
  set -a; . ./.env; set +a
fi

exec python3 - "${1:-http://localhost:${APP_PORT:-8080}}" <<'PY'
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from decimal import Decimal

BASE = sys.argv[1].rstrip("/")


class Failed(Exception):
    pass


def request(method, path, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        req.add_header(name, value)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def json_call(method, path, body=None, headers=None, expect=(200, 201)):
    status, text = request(method, path, body, headers)
    if status not in expect:
        raise Failed(f"{method} {path} -> {status}: {text[:400]}")
    return json.loads(text) if text else None


def wait_for(label, probe, timeout=120, interval=1.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            value = probe()
        except Failed:
            value = None
        if value:
            print(f"  ok   {label}")
            return value
        time.sleep(interval)
    raise Failed(f"{label}: still not true after {timeout}s")


def step(message):
    print(f"==> {message}")


def check(condition, message):
    if not condition:
        raise Failed(message)
    print(f"  ok   {message}")


try:
    step(f"Front door {BASE}")
    status, html = request("GET", "/")
    check(status == 200, "the SPA shell answers 200")
    check('<div id="root">' in html, "the SPA shell is the built React bundle")

    user = f"stack-verify-{uuid.uuid4().hex[:8]}"
    headers = {"X-User-Id": user}

    step("Catalog")
    venue = json_call("POST", "/api/catalog/venues", {
        "name": "Verification Arena", "address": "1 Verification Way", "capacity": 50})
    check(venue["name"] == "Verification Arena", f"venue {venue['id']} created")
    event = json_call("POST", "/api/catalog/events", {
        "name": "Verification Night",
        "venueId": venue["id"],
        "startsAt": "2031-01-01T19:00:00Z",
        "ticketTypes": [{"name": "General", "price": 25.00, "capacity": 10}]})
    event_id = event["id"]
    check(event["status"] == "DRAFT", f"event {event_id} created as DRAFT")
    published = json_call("POST", f"/api/catalog/events/{event_id}/publish")
    check(published["status"] == "PUBLISHED", "event published")
    listing = json_call("GET", "/api/catalog/events")
    check(any(item["id"] == event_id for item in listing["content"]), "the event is listed publicly")

    step("Booking inventory")
    availability = wait_for(
        "inventory provisioned by the EventPublished consumer",
        lambda: (lambda a: a if a["ticketTypes"] else None)(
            json_call("GET", f"/api/booking/events/{event_id}/availability", expect=(200,))))
    ticket = availability["ticketTypes"][0]
    check(ticket["available"] == 10 and ticket["held"] == 0 and ticket["sold"] == 0,
          "inventory starts with 10 available units")

    step("Waiting room")
    joined = json_call("POST", f"/api/booking/waiting-room/{event_id}/join", headers=headers)
    check(joined["status"] in ("WAITING", "ADMITTED"), f"user {user} joined the queue")
    admitted = wait_for("the dispatcher admitted the user", lambda: (lambda s: s if s["status"] == "ADMITTED" else None)(
        json_call("GET", f"/api/booking/waiting-room/{event_id}/status", headers=headers)))
    check(bool(admitted["token"]), "an admission token was issued")

    step("Reservation and payment")
    reservation = json_call("POST", "/api/booking/reservations", {
        "eventId": event_id,
        "items": [{"ticketTypeId": ticket["ticketTypeId"], "quantity": 1}]},
        headers={**headers,
                 "Idempotency-Key": str(uuid.uuid4()),
                 "X-Admission-Token": admitted["token"]})
    reservation_id = reservation["reservationId"]
    check(reservation["status"] == "HELD", f"reservation {reservation_id} held")
    checked_out = json_call("POST", f"/api/booking/reservations/{reservation_id}/checkout", headers=headers)
    check(checked_out["status"] in ("PENDING_PAYMENT", "CONFIRMED"),
          f"checkout requested payment ({checked_out['status']})")
    confirmed = wait_for("the payment saga confirmed the reservation", lambda: (lambda r: r if r["status"] == "CONFIRMED" else None)(
        json_call("GET", f"/api/booking/reservations/{reservation_id}", headers=headers)))
    check(Decimal(str(confirmed["totalAmount"])) == Decimal("25.00"), "the reservation total is 25.00")

    step("Notification")
    mailbox = wait_for("the mailbox received BOOKING_CONFIRMED", lambda: [
        n for n in json_call(
            "GET",
            "/api/notification/notifications?" + urllib.parse.urlencode({"userId": user, "size": 20}),
        )["content"] if n["type"] == "BOOKING_CONFIRMED"] or None)
    check(mailbox[0]["userId"] == user, "the notification belongs to the buyer")

    step("Inventory after the sale")
    final = json_call("GET", f"/api/booking/events/{event_id}/availability")["ticketTypes"][0]
    check(final["sold"] == 1 and final["held"] == 0 and final["available"] == 9,
          "one unit sold, none held, nine available")
    check(final["available"] + final["held"] + final["sold"] == final["capacity"],
          "available + held + sold = capacity")

    print()
    print("PASS: the documented flow works through the front door")
except Failed as error:
    print(f"FAIL: {error}", file=sys.stderr)
    sys.exit(1)
PY
