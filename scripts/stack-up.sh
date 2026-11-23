#!/usr/bin/env bash
# Builds the six images and starts the whole system in Docker Compose:
# PostgreSQL, RabbitMQ and Redis (profile "core") plus the five services and the SPA (profile "app").
#
#   ./scripts/stack-up.sh
#
# The images are built one at a time on purpose: they share one BuildKit cache mount for the Gradle
# dependencies, and concurrent Gradle builds would contend for the same cache locks.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example."
fi

# shellcheck disable=SC1091
set -a; . ./.env; set +a
APP_PORT="${APP_PORT:-8080}"
COMPOSE=(docker compose --profile core --profile app)

echo "==> Building images (sequential: they share one Gradle cache mount)"
COMPOSE_PARALLEL_LIMIT=1 "${COMPOSE[@]}" build

echo "==> Starting the stack"
"${COMPOSE[@]}" up -d

echo "==> Waiting for every healthcheck"
healthy_services() {
  local line
  while read -r line; do
    [ -n "$line" ] || continue
    case "$line" in
      *=healthy) ;;
      *) echo "$line"; return 1 ;;
    esac
  done < <("${COMPOSE[@]}" ps --format '{{.Service}}={{.Health}}')
}

for attempt in $(seq 1 90); do
  if healthy_services > /tmp/eventflow-stack-unhealthy 2>&1; then
    break
  fi
  if [ "$attempt" = 90 ]; then
    echo "Some containers are not healthy after 180s:" >&2
    cat /tmp/eventflow-stack-unhealthy >&2
    echo "Inspect them with: docker compose --profile core --profile app ps" >&2
    exit 1
  fi
  sleep 2
done
rm -f /tmp/eventflow-stack-unhealthy

cat <<EOF

EventFlow is up (Docker Compose).

  SPA            http://localhost:${APP_PORT}
  Catalog API    http://localhost:${APP_PORT}/api/catalog/events
  Booking API    http://localhost:${APP_PORT}/api/booking/events/{eventId}/availability
  Mailbox        http://localhost:${APP_PORT}/api/notification/notifications?userId=demo
  RabbitMQ UI    http://localhost:15672 (see .env)

  Logs           docker compose --profile core --profile app logs -f
  Verify         ./scripts/stack-verify.sh
  Stop           ./scripts/stack-down.sh
EOF
