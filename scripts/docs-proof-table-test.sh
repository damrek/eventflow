#!/usr/bin/env bash
# Guards the README's "how each claim is proven" table: every test it names must exist in the module
# the command next to it runs. A verifier found three rows pointing at the wrong Gradle project, so
# a reviewer following the table got "No tests found" or silently skipped the gate.
#
#   ./scripts/docs-proof-table-test.sh
set -euo pipefail

cd "$(dirname "$0")/.."

failures=0
check() { # <gradle-module> <test-class>
  if find "services/${1#:}" -name "${2}.java" | grep -q .; then
    printf 'ok   %s lives in %s\n' "$2" "$1"
  else
    printf 'FAIL %s is not in %s\n' "$2" "$1" >&2
    failures=$((failures + 1))
  fi
}

# The module names below must match the command in the README row, not the service that publishes
# the event: `EventPublishedConsumerTest` and `PaymentSagaIntegrationTest` are Booking's tests.
for pair in \
  'booking:ReservationConcurrencyTest' \
  'catalog:EventPublicationIntegrationTest' \
  'notification:NotificationConsumerTest' \
  'booking:EventPublishedConsumerTest' \
  'payment:PaymentConsumerTest' \
  'payment:PaymentGatewayCircuitBreakerTest' \
  'booking:PaymentSagaIntegrationTest' \
  'booking:WaitingRoomIntegrationTest' \
  'booking:WaitingRoomRateLimitTest' \
  'booking:RateLimitSharedStateTest' \
  'booking:WaitingRoomRedisOutageTest'; do
  check "${pair%%:*}" "${pair#*:}"
done

if [ "$failures" -ne 0 ]; then
  printf '%d README proof row(s) point at the wrong module\n' "$failures" >&2
  exit 1
fi
echo 'PASS: the README proof table matches the Gradle modules'
