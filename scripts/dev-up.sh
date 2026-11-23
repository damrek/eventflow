#!/usr/bin/env bash
# Starts the five backend services and the SPA in the background, with one log per process under
# build/dev/. Infrastructure comes from Docker Compose (profile "core") and the service databases
# are created idempotently, so a volume from an earlier phase keeps its data.
#
#   ./scripts/dev-up.sh          start everything and wait until it answers
#   ./scripts/dev-down.sh        stop what this script started
#   ./scripts/dev-down.sh --infra  also stop PostgreSQL, RabbitMQ and Redis (never deletes data)
#
# A service that is already healthy on its port is reused and not touched by dev-down.
set -euo pipefail

cd "$(dirname "$0")/.."

DEV_DIR="build/dev"
PIDS="$DEV_DIR/pids"
mkdir -p "$DEV_DIR"

if [ -s "$PIDS" ]; then
  running=0
  while read -r _name pid; do
    [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null && running=1
  done < "$PIDS"
  if [ "$running" = 1 ]; then
    echo "A dev-up session is already running ($PIDS). Use scripts/dev-down.sh first." >&2
    exit 1
  fi
  rm -f "$PIDS"
fi
: > "$PIDS"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  for candidate in "$HOME"/.jdks/*/bin/java /usr/lib/jvm/*/bin/java; do
    if [ -x "$candidate" ]; then
      export JAVA_HOME="$(dirname "$(dirname "$candidate")")"
      break
    fi
  done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Java 21 not found. Export JAVA_HOME (for example ~/.jdks/graalvm-ce-21.0.2)." >&2
  exit 1
fi
JAVA_BIN="$JAVA_HOME/bin/java"

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example."
fi

echo "==> Infrastructure"
docker compose --profile core up -d

wait_for_postgres() {
  local attempt
  for attempt in $(seq 1 30); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' eventflow-postgres-1 2>/dev/null || true)" = "healthy" ] && return 0
    sleep 2
  done
  echo "PostgreSQL did not become healthy." >&2
  return 1
}
wait_for_postgres

# A container started before a checkout can be bound to a replaced (now empty) init directory;
# recreating it keeps the named data volume and restores the mount.
if ! docker compose exec -T postgres test -f /docker-entrypoint-initdb.d/01-create-databases.sh 2>/dev/null; then
  echo "    recreating postgres: the init directory inside the container is stale"
  docker compose up -d --force-recreate postgres
  wait_for_postgres
fi
docker compose exec -T postgres sh /docker-entrypoint-initdb.d/01-create-databases.sh

echo "==> Building service jars"
./gradlew --console=plain \
  :catalog:bootJar :booking:bootJar :notification:bootJar \
  :payment:bootJar :payment-simulator:bootJar

# The services read the same variables Compose does; defaults live in application.yml.
set -a
# shellcheck disable=SC1091
. ./.env
set +a

# Runs in the background with its own session, so dev-down can stop the whole process group.
start_process() {
  local name="$1" log="$DEV_DIR/$1.log"
  shift
  setsid "$@" > "$log" 2>&1 < /dev/null &
  echo "$name $!" >> "$PIDS"
}

port_busy() {
  ss -ltn 2>/dev/null | grep -q ":$1 "
}

start_service() {
  local name="$1" port="$2" jar
  if curl -fsS --max-time 2 "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
    echo "    $name already answers on :$port (left untouched)"
    return 0
  fi
  if port_busy "$port"; then
    echo "    port $port is busy but $name is not healthy; stop that process first" >&2
    return 1
  fi
  jar="$(find "services/$name/build/libs" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | sort | head -1)"
  if [ -z "$jar" ]; then
    echo "    no runnable jar for $name" >&2
    return 1
  fi
  echo "    starting $name on :$port ($DEV_DIR/$name.log)"
  start_process "$name" "$JAVA_BIN" -jar "$jar"
}

echo "==> Backend"
start_service catalog 8081
start_service booking 8082
start_service notification 8083
start_service payment 8084
start_service payment-simulator 8085

probe_health() {
  curl -fsS --max-time 2 "http://localhost:$1/actuator/health" >/dev/null 2>&1
}
probe_simulator() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:8085/ 2>/dev/null || true)"
  [ -n "$code" ] && [ "$code" != "000" ]
}

wait_until() {
  local label="$1" attempts="$2"
  shift 2
  local attempt
  for attempt in $(seq 1 "$attempts"); do
    "$@" && { echo "    $label ready"; return 0; }
    sleep 2
  done
  echo "    $label did not become ready in time" >&2
  return 1
}

echo "==> Waiting for readiness"
if ! wait_until "catalog :8081" 60 probe_health 8081 \
  || ! wait_until "booking :8082" 60 probe_health 8082 \
  || ! wait_until "notification :8083" 60 probe_health 8083 \
  || ! wait_until "payment :8084" 60 probe_health 8084 \
  || ! wait_until "payment-simulator :8085" 30 probe_simulator; then
  echo "Some services failed to start. Last lines:" >&2
  for log in "$DEV_DIR"/*.log; do
    echo "--- $log" >&2
    tail -n 8 "$log" >&2 || true
  done
  echo "Run scripts/dev-down.sh, fix the cause and retry." >&2
  exit 1
fi

echo "==> Frontend"
if [ ! -d frontend/node_modules ]; then
  (cd frontend && npm ci)
fi
if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:5173/ 2>/dev/null || true)" = "200" ]; then
  echo "    the SPA already answers on :5173 (left untouched)"
elif port_busy 5173; then
  echo "    port 5173 is busy but the SPA does not answer; stop that process first" >&2
  exit 1
else
  echo "    starting the SPA on :5173 ($DEV_DIR/frontend.log)"
  start_process frontend bash -c 'cd frontend && exec npm run dev -- --host 127.0.0.1 --port 5173'
  wait_until "SPA :5173" 60 bash -c '[ "$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 http://localhost:5173/)" = "200" ]'
fi

cat <<EOF

EventFlow is up.

  SPA           http://localhost:5173
  Catalog       http://localhost:8081/swagger-ui.html
  Booking       http://localhost:8082/swagger-ui.html
  Notification  http://localhost:8083/swagger-ui.html
  RabbitMQ      http://localhost:15672 (eventflow / eventflow)

  Logs          tail -f $DEV_DIR/*.log
  Stop          ./scripts/dev-down.sh
  Seed data     see "Seed a demo event" in README.md
EOF
