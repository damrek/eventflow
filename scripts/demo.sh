#!/usr/bin/env bash
# The documented booking flow, executed end to end against the running stack, asserting every state
# it claims. It is the demo and a smoke test at once: the moment an expected state does not appear it
# exits non-zero instead of printing a wall of output.
#
#   ./scripts/demo.sh                                  # against http://localhost:8080
#   BASE_URL=http://localhost:4173 ./scripts/demo.sh   # against `npm run preview`
#
# Requires `jq` and a stack that is already up (`./scripts/stack-up.sh` or `./scripts/dev-up.sh`).
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
DEMO_USER="${DEMO_USER:-demo-user}"
CATALOG="${BASE_URL}/api/catalog"
BOOKING="${BASE_URL}/api/booking"
NOTIFICATION="${BASE_URL}/api/notification"
RUN_ID="$(date +%s)"
IDEMPOTENCY_KEY="demo-${RUN_ID}"
CAPACITY=300

step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
fail() { printf 'FAILED: %s\n' "$1" >&2; exit 1; }

command -v jq >/dev/null 2>&1 || fail 'jq is required for this script'
curl -fsS --max-time 5 "${BASE_URL}/healthz" >/dev/null 2>&1 ||
  fail "the stack is not answering at ${BASE_URL}; run ./scripts/stack-up.sh first"

step '1/8 Catalog: a venue and a draft event'
VENUE=$(curl -fsS -X POST "${CATALOG}/venues" -H 'Content-Type: application/json' \
  -d '{"name":"Demo Arena","address":"Calle 1, Madrid","capacity":300}' | jq -r '.id')
STARTS_AT=$(date -u -d '+30 days' '+%Y-%m-%dT%H:%M:%SZ')
EVENT=$(curl -fsS -X POST "${CATALOG}/events" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Demo Concert ${RUN_ID}\",\"venueId\":\"${VENUE}\",\"startsAt\":\"${STARTS_AT}\",\"ticketTypes\":[{\"name\":\"General\",\"price\":25.00,\"capacity\":${CAPACITY}}]}" |
  jq -r '.id')
printf '   venue %s, event %s in DRAFT\n' "${VENUE}" "${EVENT}"

step '2/8 Publish: Catalog writes EventPublished in the event transaction (ADR 0009)'
curl -fsS -X POST "${CATALOG}/events/${EVENT}/publish" >/dev/null
printf '   event published; Booking has not seen it yet\n'

step '3/8 Booking provisions its inventory from the broker hop'
deadline=$((SECONDS + 30))
while :; do
  AVAILABILITY=$(curl -fsS "${BOOKING}/events/${EVENT}/availability" 2>/dev/null || true)
  TYPE=$(printf '%s' "${AVAILABILITY}" | jq -r '.ticketTypes[0].ticketTypeId // empty' 2>/dev/null || true)
  if [ -n "${TYPE}" ]; then break; fi
  [ "${SECONDS}" -lt "${deadline}" ] || fail 'Booking did not provision the inventory in 30s'
  sleep 1
done
printf '   ticket type %s: %s available of %s\n' "${TYPE}" \
  "$(printf '%s' "${AVAILABILITY}" | jq -r '.ticketTypes[0].available')" \
  "$(printf '%s' "${AVAILABILITY}" | jq -r '.ticketTypes[0].capacity')"

step '4/8 Waiting room: join and wait for admission (Redis, ADR 0011)'
curl -fsS -X POST "${BOOKING}/waiting-room/${EVENT}/join" -H "X-User-Id: ${DEMO_USER}" >/dev/null
deadline=$((SECONDS + 60))
while :; do
  STATUS=$(curl -fsS "${BOOKING}/waiting-room/${EVENT}/status" -H "X-User-Id: ${DEMO_USER}")
  ROOM_STATUS=$(printf '%s' "${STATUS}" | jq -r '.status')
  TOKEN=$(printf '%s' "${STATUS}" | jq -r '.token // empty')
  if [ "${ROOM_STATUS}" = 'ADMITTED' ] && [ -n "${TOKEN}" ]; then break; fi
  [ "${SECONDS}" -lt "${deadline}" ] || fail "the waiting room never admitted the user (status ${ROOM_STATUS})"
  sleep 1
done
printf '   admitted; the admission token is required while the queue exists\n'

step '5/8 Hold two tickets with one Idempotency-Key'
HOLD_BODY="{\"eventId\":\"${EVENT}\",\"items\":[{\"ticketTypeId\":\"${TYPE}\",\"quantity\":2}]}"
RESERVATION=$(curl -fsS -X POST "${BOOKING}/reservations" \
  -H 'Content-Type: application/json' \
  -H "X-User-Id: ${DEMO_USER}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -H "X-Admission-Token: ${TOKEN}" \
  -d "${HOLD_BODY}")
RESERVATION_ID=$(printf '%s' "${RESERVATION}" | jq -r '.reservationId')
printf '   reservation %s is %s, expires %s\n' "${RESERVATION_ID}" \
  "$(printf '%s' "${RESERVATION}" | jq -r '.status')" \
  "$(printf '%s' "${RESERVATION}" | jq -r '.expiresAt')"

step '6/8 Replay the same request: the same key returns the same reservation'
REPLAY_ID=$(curl -fsS -X POST "${BOOKING}/reservations" \
  -H 'Content-Type: application/json' \
  -H "X-User-Id: ${DEMO_USER}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -H "X-Admission-Token: ${TOKEN}" \
  -d "${HOLD_BODY}" | jq -r '.reservationId')
[ "${REPLAY_ID}" = "${RESERVATION_ID}" ] || fail "the replay created ${REPLAY_ID} instead of ${RESERVATION_ID}"
printf '   no second hold was taken\n'

step '7/8 Checkout: the saga requests the payment and waits for the provider'
curl -fsS -X POST "${BOOKING}/reservations/${RESERVATION_ID}/checkout" -H "X-User-Id: ${DEMO_USER}" >/dev/null
deadline=$((SECONDS + 60))
while :; do
  RESERVATION_STATE=$(curl -fsS "${BOOKING}/reservations/${RESERVATION_ID}" -H "X-User-Id: ${DEMO_USER}")
  RESERVATION_STATUS=$(printf '%s' "${RESERVATION_STATE}" | jq -r '.status')
  case "${RESERVATION_STATUS}" in
    CONFIRMED) break ;;
    PAYMENT_FAILED) fail 'the simulated provider rejected the payment' ;;
  esac
  [ "${SECONDS}" -lt "${deadline}" ] || fail "the reservation stayed ${RESERVATION_STATUS} for 60s"
  sleep 1
done
printf '   reservation %s is CONFIRMED (units held -> sold)\n' "${RESERVATION_ID}"

step '8/8 Notification built the mailbox entry from booking.confirmed'
deadline=$((SECONDS + 30))
while :; do
  MAILBOX=$(curl -fsS "${NOTIFICATION}/notifications?userId=${DEMO_USER}&page=0&size=20")
  MESSAGE=$(printf '%s' "${MAILBOX}" | jq -r --arg id "${RESERVATION_ID}" \
    '.content[] | select(.content | contains($id)) | .content' | head -1)
  if [ -n "${MESSAGE}" ]; then break; fi
  [ "${SECONDS}" -lt "${deadline}" ] || fail 'the mailbox never received the confirmation'
  sleep 1
done
printf '   "%s"\n' "${MESSAGE}"

AVAILABILITY=$(curl -fsS "${BOOKING}/events/${EVENT}/availability")
AVAILABLE=$(printf '%s' "${AVAILABILITY}" | jq -r '.ticketTypes[0].available')
HELD=$(printf '%s' "${AVAILABILITY}" | jq -r '.ticketTypes[0].held')
SOLD=$(printf '%s' "${AVAILABILITY}" | jq -r '.ticketTypes[0].sold')
[ "${SOLD}" = '2' ] || fail "expected 2 sold, found ${SOLD}"
[ "${AVAILABLE}" = "$((CAPACITY - 2))" ] || fail "expected $((CAPACITY - 2)) available, found ${AVAILABLE}"
printf '\n   availability: available %s, held %s, sold %s (capacity %s)\n' \
  "${AVAILABLE}" "${HELD}" "${SOLD}" "${CAPACITY}"

printf '\nPASS: the documented flow works end to end.\n'
