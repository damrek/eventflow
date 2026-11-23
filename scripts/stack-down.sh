#!/usr/bin/env bash
# Stops the container stack (profiles "core" and "app").
#
#   ./scripts/stack-down.sh            stop the containers, keep the data volumes
#   ./scripts/stack-down.sh --volumes  also delete the PostgreSQL and RabbitMQ volumes
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "${1:-}" = "--volumes" ]; then
  docker compose --profile core --profile app down --volumes
else
  docker compose --profile core --profile app down
fi
