#!/usr/bin/env bash
# Starts the whole system with observability: PostgreSQL, RabbitMQ and Redis (profile "core"), the
# five services and the SPA (profile "app"), plus Prometheus, Grafana, the OpenTelemetry collector
# and Tempo (profile "observability"), with tracing export on.
#
#   ./scripts/observability-up.sh
#
# Building is sequential because the six images share one Gradle cache mount; concurrent builds
# would contend for the same cache locks. The services post traces only here, because this is the
# only stack that runs a collector.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example."
fi

# shellcheck disable=SC1091
set -a; . ./.env; set +a

APP_PORT="${APP_PORT:-8080}"
GRAFANA_PORT="${GRAFANA_PORT:-3000}"
PROMETHEUS_PORT="${PROMETHEUS_PORT:-9090}"
COMPOSE=(docker compose --profile core --profile app --profile observability)

# Only this stack has a collector, so only this stack exports spans.
export OTEL_TRACES_ENABLED=true

echo "==> Building images (sequential: they share one Gradle cache mount)"
COMPOSE_PARALLEL_LIMIT=1 "${COMPOSE[@]}" build

echo "==> Starting the stack"
"${COMPOSE[@]}" up -d

echo "==> Waiting for the containers with a healthcheck"
unhealthy_services() {
  local line
  while read -r line; do
    [ -n "$line" ] || continue
    case "$line" in
      *=healthy|*=) ;;
      *) echo "$line"; return 1 ;;
    esac
  done < <("${COMPOSE[@]}" ps --format '{{.Service}}={{.Health}}')
}

for attempt in $(seq 1 120); do
  if unhealthy_services > /tmp/eventflow-observability-unhealthy 2>&1; then
    break
  fi
  if [ "$attempt" = 120 ]; then
    echo "Some containers are not healthy after 240s:" >&2
    cat /tmp/eventflow-observability-unhealthy >&2
    echo "Inspect them with: docker compose --profile core --profile app --profile observability ps" >&2
    exit 1
  fi
  sleep 2
done
rm -f /tmp/eventflow-observability-unhealthy

echo "==> Waiting for Prometheus, Grafana and the front door"
wait_for() {
  local label="$1" url="$2"
  for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then
      echo "  ok   $label"
      return 0
    fi
    sleep 2
  done
  echo "  !!   $label did not answer: $url" >&2
  return 1
}
wait_for "Prometheus  http://localhost:${PROMETHEUS_PORT}/-/ready" "http://localhost:${PROMETHEUS_PORT}/-/ready"
wait_for "Grafana     http://localhost:${GRAFANA_PORT}/api/health" "http://localhost:${GRAFANA_PORT}/api/health"
wait_for "Front door  http://localhost:${APP_PORT}/" "http://localhost:${APP_PORT}/"

cat <<EOF

EventFlow is up with observability (Docker Compose).

  Grafana        http://localhost:${GRAFANA_PORT}    (dashboards under the EventFlow folder)
  Prometheus     http://localhost:${PROMETHEUS_PORT}
  SPA            http://localhost:${APP_PORT}
  RabbitMQ UI    http://localhost:15672 (see .env)

  Tempo and the OpenTelemetry collector stay on the Compose network; the trace of a booking is
  visible from Grafana (Explore -> Tempo) and proven by the script below.

  Logs           docker compose --profile core --profile app --profile observability logs -f
  Verify         ./scripts/observability-verify.sh
  Stop           ./scripts/observability-down.sh [--volumes]
EOF
