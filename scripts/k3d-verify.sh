#!/usr/bin/env bash
# Proves the phase 6 gate against the real cluster:
#
#   1. the documented flow works through the Ingress (delegated to stack-verify.sh);
#   2. Booking runs two replicas and both answer;
#   3. an admission token issued by one replica is accepted by the other (shared Redis);
#   4. twenty concurrent holds split across both replicas never oversell;
#   5. a deleted Booking pod is replaced while the Ingress keeps serving;
#   6. a Booking replica whose database is gone leaves readiness without restarting.
#
#   ./scripts/k3d-verify.sh
#
# Everything is restored on exit: PostgreSQL is scaled back to one replica and the port-forwards are
# closed even when a check fails.
set -euo pipefail

cd "$(dirname "$0")/.."

CLUSTER="${K3D_CLUSTER:-eventflow}"
INGRESS_PORT="${K3D_INGRESS_PORT:-8090}"
BASE="http://localhost:${INGRESS_PORT}"
POD_A_PORT=18082
POD_B_PORT=28082

PF_A=""
PF_B=""

cleanup() {
  kubectl -n eventflow scale statefulset/postgres --replicas=1 >/dev/null 2>&1 || true
  [ -n "${PF_A}" ] && kill "${PF_A}" 2>/dev/null || true
  [ -n "${PF_B}" ] && kill "${PF_B}" 2>/dev/null || true
}
trap cleanup EXIT

ready_replicas() {
  kubectl -n eventflow get deployment booking -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo 0
}

wait_ready() {
  local wanted="$1" attempts="${2:-60}"
  for _ in $(seq 1 "${attempts}"); do
    [ "$(ready_replicas)" = "${wanted}" ] && return 0
    sleep 2
  done
  echo "booking never reached ${wanted} ready replicas (last: $(ready_replicas))" >&2
  return 1
}

echo "==> Cluster state"
for deployment in catalog booking notification payment payment-simulator frontend; do
  kubectl -n eventflow rollout status "deployment/${deployment}" --timeout=300s >/dev/null
done
wait_ready 2 90
echo "  ok   booking has 2 ready replicas"
mapfile -t PODS < <(kubectl -n eventflow get pods -l app=booking -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
if [ "${#PODS[@]}" -ne 2 ]; then
  echo "expected 2 booking pods, found ${#PODS[@]}" >&2
  exit 1
fi
echo "  ok   booking pods: ${PODS[0]} ${PODS[1]}"

echo "==> Documented flow through the Ingress"
./scripts/stack-verify.sh "${BASE}"

echo "==> Cross-replica proving ground"
kubectl -n eventflow port-forward "pod/${PODS[0]}" "${POD_A_PORT}:8082" >/tmp/eventflow-pf-a.log 2>&1 &
PF_A=$!
kubectl -n eventflow port-forward "pod/${PODS[1]}" "${POD_B_PORT}:8082" >/tmp/eventflow-pf-b.log 2>&1 &
PF_B=$!
for _ in $(seq 1 30); do
  if curl -fsS --max-time 2 "http://localhost:${POD_A_PORT}/actuator/health/liveness" >/dev/null 2>&1 \
    && curl -fsS --max-time 2 "http://localhost:${POD_B_PORT}/actuator/health/liveness" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
echo "  ok   both replicas are reachable directly (${PODS[0]}, ${PODS[1]})"

python3 - "${BASE}" "http://localhost:${POD_A_PORT}" "http://localhost:${POD_B_PORT}" <<'PY'
import json
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

INGRESS, POD_A, POD_B = sys.argv[1].rstrip("/"), sys.argv[2].rstrip("/"), sys.argv[3].rstrip("/")


class Failed(Exception):
    pass


def request(base, method, path, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        req.add_header(name, value)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def json_call(base=INGRESS, method="GET", path="/", body=None, headers=None, expect=(200, 201)):
    status, text = request(base, method, path, body, headers)
    if status not in expect:
        raise Failed(f"{method} {base}{path} -> {status}: {text[:300]}")
    return json.loads(text) if text else None


def booking(base, method, path, body=None, headers=None, expect=(200, 201)):
    """Calls Booking on the Ingress (prefixed) or directly on a pod (unprefixed)."""
    prefix = "/api/booking" if base == INGRESS else "/api"
    return json_call(base, method, prefix + path, body, headers, expect)


def wait_for(label, probe, timeout=120, interval=1.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            value = probe()
        except Failed:
            value = None
        if value:
            print(f"  ok   {label}")
            return value
        time.sleep(interval)
    raise Failed(f"{label}: not true after {timeout}s")


def check(condition, message):
    if not condition:
        raise Failed(message)
    print(f"  ok   {message}")


def create_event(capacity):
    venue = json_call(method="POST", path="/api/catalog/venues", body={
        "name": "Cluster Arena", "address": "2 Cluster Way", "capacity": 200})
    event = json_call(method="POST", path="/api/catalog/events", body={
        "name": "Cluster Night", "venueId": venue["id"], "startsAt": "2031-02-01T19:00:00Z",
        "ticketTypes": [{"name": "General", "price": 25.00, "capacity": capacity}]})
    json_call(method="POST", path=f"/api/catalog/events/{event['id']}/publish")
    availability = wait_for(f"inventory of event {event['id']} provisioned", lambda: (
        lambda a: a if a["ticketTypes"] else None)(
        json_call(path=f"/api/booking/events/{event['id']}/availability", expect=(200,))))
    return event["id"], availability["ticketTypes"][0]["ticketTypeId"]


try:
    # --- 3. an admission token minted by one replica is accepted by the other -------------------
    event_id, ticket_type_id = create_event(5)
    user = f"cluster-token-{uuid.uuid4().hex[:8]}"
    headers = {"X-User-Id": user}
    booking(POD_A, "POST", f"/waiting-room/{event_id}/join", headers=headers)
    admitted = wait_for("replica A admitted the user", lambda: (
        lambda s: s if s["status"] == "ADMITTED" else None)(
        booking(POD_A, "GET", f"/waiting-room/{event_id}/status", headers=headers)))
    reservation = booking(POD_B, "POST", "/reservations", body={
        "eventId": event_id, "items": [{"ticketTypeId": ticket_type_id, "quantity": 1}]},
        headers={**headers, "Idempotency-Key": str(uuid.uuid4()),
                 "X-Admission-Token": admitted["token"]})
    check(reservation["status"] == "HELD",
          "a token admitted by replica A is honoured by replica B (shared Redis state)")

    # --- 4. twenty concurrent holds across both replicas never oversell ---------------------------
    event_id, ticket_type_id = create_event(10)
    results = []
    lock = threading.Lock()

    def hold(index):
        base = POD_A if index % 2 == 0 else POD_B
        status, _ = request(base, "POST", "/api/reservations", {
            "eventId": event_id, "items": [{"ticketTypeId": ticket_type_id, "quantity": 1}]},
            {"X-User-Id": f"cluster-race-{index}", "Idempotency-Key": str(uuid.uuid4())})
        with lock:
            results.append(status)

    threads = [threading.Thread(target=hold, args=(i,)) for i in range(20)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    held = results.count(201)
    rejected = results.count(422)
    check(held == 10 and rejected == 10,
          f"20 concurrent holds on 10 units across both replicas: {held} held, {rejected} INSUFFICIENT_TICKETS")
    final = json_call(path=f"/api/booking/events/{event_id}/availability")["ticketTypes"][0]
    check(final["available"] == 0 and final["held"] == 10 and final["sold"] == 0,
          "no oversell: 10 held, 0 available, 0 sold")
    check(final["available"] + final["held"] + final["sold"] == final["capacity"],
          "available + held + sold = capacity")

    print()
    print("PASS: both replicas serve, share admission state and never oversell")
except Failed as error:
    print(f"FAIL: {error}", file=sys.stderr)
    sys.exit(1)
PY

echo "==> Pod replacement"
VICTIM="${PODS[0]}"
kubectl -n eventflow delete pod "${VICTIM}" --wait=false >/dev/null
# The old pod must be gone before the readiness check, otherwise a replacement pod can start while
# PostgreSQL is down and its Flyway run would count as a liveness restart.
kubectl -n eventflow wait --for=delete "pod/${VICTIM}" --timeout=180s >/dev/null 2>&1 || true
wait_ready 2 90
kubectl -n eventflow rollout status deployment/booking --timeout=180s >/dev/null
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${BASE}/api/catalog/events")
if [ "${code}" != "200" ]; then
  echo "the front door stopped answering after the pod deletion (catalog -> ${code})" >&2
  exit 1
fi
echo "  ok   ${VICTIM} was replaced and the Ingress kept serving"

echo "==> Readiness depends on the database"
restarts() {
  kubectl -n eventflow get pods -l app=booking \
    -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[0].restartCount}{"\n"}{end}' | sort
}
before=$(restarts)
kubectl -n eventflow scale statefulset/postgres --replicas=0 >/dev/null
left_readiness=0
flags=""
for _ in $(seq 1 60); do
  flags=$(kubectl -n eventflow get pods -l app=booking -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{" "}{end}')
  # Every replica must report false: that is the readiness group reading the database.
  if [ -n "${flags}" ] && ! echo "${flags}" | grep -q true; then
    left_readiness=1
    break
  fi
  sleep 2
done
if [ "${left_readiness}" != 1 ]; then
  echo "booking stayed ready without its database (${flags})" >&2
  exit 1
fi
echo "  ok   booking replicas left readiness with no database (${flags})"
after=$(restarts)
# Only pods that existed before the outage are compared: a replacement created during the window is
# not evidence about liveness.
increased=$(join <(echo "${before}") <(echo "${after}") | awk '$2 != $3 {print $0}')
if [ -n "${increased}" ]; then
  echo "liveness restarted a replica while the database was gone:" >&2
  echo "${increased}" >&2
  exit 1
fi
echo "  ok   liveness did not restart a replica: readiness owned the failure"

kubectl -n eventflow scale statefulset/postgres --replicas=1 >/dev/null
kubectl -n eventflow rollout status statefulset/postgres --timeout=300s >/dev/null
wait_ready 2 120
echo "  ok   the replicas became ready again after the database returned"

echo
echo "PASS: the phase 6 gate holds on the cluster"
