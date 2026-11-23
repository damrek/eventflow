#!/usr/bin/env bash
# Stops the processes started by scripts/dev-up.sh. Infrastructure stays up unless --infra is
# given; the PostgreSQL volume is never deleted.
set -euo pipefail

cd "$(dirname "$0")/.."

PIDS="build/dev/pids"

if [ ! -f "$PIDS" ] || [ ! -s "$PIDS" ]; then
  echo "Nothing to stop: no $PIDS."
  if [ "${1:-}" = "--infra" ]; then
    echo "==> Stopping infrastructure"
    docker compose --profile core down
  fi
  exit 0
fi

echo "==> Stopping application processes"
while read -r name pid; do
  [ -n "${pid:-}" ] || continue
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "    $name ($pid) already stopped"
    continue
  fi
  echo "    $name ($pid)"
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
done < "$PIDS"

for _attempt in $(seq 1 15); do
  alive=0
  while read -r _name pid; do
    [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null && alive=1
  done < "$PIDS"
  [ "$alive" = 0 ] && break
  sleep 1
done

while read -r name pid; do
  [ -n "${pid:-}" ] || continue
  if kill -0 "$pid" 2>/dev/null; then
    echo "    forcing $name ($pid)"
    kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
  fi
done < "$PIDS"

rm -f "$PIDS"

if [ "${1:-}" = "--infra" ]; then
  echo "==> Stopping infrastructure"
  docker compose --profile core down
else
  echo "    PostgreSQL, RabbitMQ and Redis keep running (./scripts/dev-down.sh --infra to stop them)."
fi
