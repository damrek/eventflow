#!/usr/bin/env bash
# Stops the local cluster.
#
#   ./scripts/k3d-down.sh                    stop the cluster (keeps it for a fast restart)
#   ./scripts/k3d-down.sh --delete-cluster   delete the cluster and its volumes
set -euo pipefail

cd "$(dirname "$0")/.."

CLUSTER="${K3D_CLUSTER:-eventflow}"

case "${1:-}" in
  --delete-cluster)
    k3d cluster delete "${CLUSTER}"
    ;;
  *)
    k3d cluster stop "${CLUSTER}"
    ;;
esac
