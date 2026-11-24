#!/usr/bin/env bash
# Stops the stack started by ./scripts/observability-up.sh (profiles "core", "app" and
# "observability").
#
#   ./scripts/observability-down.sh            stop the containers, keep the volumes
#   ./scripts/observability-down.sh --volumes  also delete the data volumes (databases, metrics, traces)
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "${1:-}" = "--volumes" ]; then
  docker compose --profile core --profile app --profile observability down --volumes
else
  docker compose --profile core --profile app --profile observability down
fi
