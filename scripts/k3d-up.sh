#!/usr/bin/env bash
# Creates the k3d cluster, builds the images, imports them and applies deploy/k8s.
#
#   ./scripts/k3d-up.sh
#
# Prerequisites: docker, k3d and kubectl. The cluster is self-contained: PostgreSQL, RabbitMQ and
# Redis run inside it, so nothing from the Compose stack is reused.
set -euo pipefail

cd "$(dirname "$0")/.."

CLUSTER="${K3D_CLUSTER:-eventflow}"
INGRESS_PORT="${K3D_INGRESS_PORT:-8090}"

for tool in docker k3d kubectl; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is required. Install it, then re-run this script." >&2
    exit 1
  fi
done

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example."
fi
# shellcheck disable=SC1091
set -a; . ./.env; set +a

if k3d cluster list -o json | grep -q "\"name\":\"${CLUSTER}\""; then
  echo "==> Reusing cluster ${CLUSTER}"
  if ! k3d cluster list -o json | grep -q "\"HostPort\":\"${INGRESS_PORT}\""; then
    echo "The cluster exists without the ${INGRESS_PORT} load-balancer mapping." >&2
    echo "Delete it with ./scripts/k3d-down.sh --delete-cluster and re-run." >&2
    exit 1
  fi
else
  echo "==> Creating cluster ${CLUSTER} (1 server, 1 agent, ingress on :${INGRESS_PORT})"
  k3d cluster create "${CLUSTER}" \
    --servers 1 \
    --agents 1 \
    --port "${INGRESS_PORT}:80@loadbalancer" \
    --wait
fi

echo "==> Namespace and credentials"
kubectl create namespace eventflow --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# The Secret is never committed: it is built from .env on every run, so rotating a password means
# editing .env and re-running this script.
kubectl -n eventflow create secret generic eventflow-secrets \
  --from-literal=POSTGRES_USER="${POSTGRES_USER:-eventflow}" \
  --from-literal=POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-eventflow}" \
  --from-literal=RABBITMQ_USER="${RABBITMQ_USER:-eventflow}" \
  --from-literal=RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-eventflow}" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

echo "==> Building images (sequential: they share one Gradle cache mount)"
export EVENTFLOW_TAG=local
# Both profiles are named because Compose refuses to resolve `depends_on` against a service whose
# profile is not enabled, even for a build; only the six listed services are actually built.
COMPOSE_PARALLEL_LIMIT=1 docker compose --profile core --profile app build \
  catalog booking notification payment payment-simulator
# The cluster runs the static target: the Ingress owns API routing, so nginx has no upstream to
# resolve when the pod starts.
docker build --target runtime-static -t "eventflow/frontend:local" ./frontend

echo "==> Importing images into the cluster"
k3d image import -c "${CLUSTER}" \
  eventflow/catalog:local \
  eventflow/booking:local \
  eventflow/notification:local \
  eventflow/payment:local \
  eventflow/payment-simulator:local \
  eventflow/frontend:local

echo "==> Applying deploy/k8s"
kubectl apply -k deploy/k8s

echo "==> Waiting for the stateful dependencies"
kubectl -n eventflow rollout status statefulset/postgres --timeout=300s
kubectl -n eventflow rollout status statefulset/rabbitmq --timeout=300s
kubectl -n eventflow rollout status deployment/redis --timeout=120s

echo "==> Waiting for the services"
for deployment in catalog booking notification payment payment-simulator frontend; do
  kubectl -n eventflow rollout status "deployment/${deployment}" --timeout=600s
done

cat <<EOF

EventFlow is up (k3d).

  SPA            http://localhost:${INGRESS_PORT}
  Catalog API    http://localhost:${INGRESS_PORT}/api/catalog/events
  Booking API    http://localhost:${INGRESS_PORT}/api/booking/events/{eventId}/availability
  Mailbox        http://localhost:${INGRESS_PORT}/api/notification/notifications?userId=demo
  Pods           kubectl -n eventflow get pods -o wide
  Verify         ./scripts/k3d-verify.sh
  Stop           ./scripts/k3d-down.sh
EOF
