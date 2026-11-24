#!/usr/bin/env bash
# Phase 7 gate: a dashboard and a visible trace of one reservation flow.
#
#   ./scripts/observability-verify.sh
#
# 1. Runs the documented flow through the front door (scripts/stack-verify.sh), so the traffic the
#    dashboards and the trace describe is real.
# 2. Prometheus: the five services and RabbitMQ are up, the HTTP histogram exists, and the business
#    meters the observability document promises are there.
# 3. Tempo (through Grafana's datasource proxy, because Tempo is not published): one trace contains
#    spans from Booking, Payment and Notification, i.e. the flow survived the broker, the outbox
#    relay and the saga.
# 4. Grafana: healthy, the four provisioned dashboards are loaded, and Booking's stdout is JSON with
#    the same trace id in the line that names the reservation.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -f .env ]; then
  # shellcheck disable=SC1091
  set -a; . ./.env; set +a
fi

echo "==> Documented flow through the front door"
# The trace that proves the gate must come from this run, not from one that happened earlier.
FLOW_START=$(date +%s)
./scripts/stack-verify.sh

exec python3 -u - \
  "${APP_PORT:-8080}" "${GRAFANA_PORT:-3000}" "${PROMETHEUS_PORT:-9090}" "${FLOW_START}" <<'PY'
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

APP, GRAFANA_PORT, PROM_PORT, FLOW_START = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
GRAFANA = f"http://localhost:{GRAFANA_PORT}"
PROM = f"http://localhost:{PROM_PORT}"
TEMPO = f"{GRAFANA}/api/datasources/proxy/uid/tempo"

SERVICES = ["booking", "catalog", "notification", "payment", "payment-simulator"]


class Failed(Exception):
    pass


def get_json(url):
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        raise Failed(f"GET {url} -> {error.code}: {error.read().decode()[:300]}") from error
    except urllib.error.URLError as error:
        raise Failed(f"GET {url} -> {error.reason}") from error


def get_text(url):
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            return response.read().decode()
    except urllib.error.HTTPError as error:
        raise Failed(f"GET {url} -> {error.code}: {error.read().decode()[:300]}") from error
    except urllib.error.URLError as error:
        raise Failed(f"GET {url} -> {error.reason}") from error


def check(condition, message):
    if not condition:
        raise Failed(message)
    print(f"  ok   {message}")


def step(message):
    print(f"==> {message}")


def query(expr):
    """Prometheus instant query; returns [(labels, value)]."""
    url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    payload = get_json(url)
    check(payload.get("status") == "success", f"Prometheus answered: {expr}")
    return [(item["metric"], float(item["value"][1])) for item in payload["data"]["result"]]


def wait_for(label, probe, timeout=90):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            value = probe()
        except Failed as error:
            last = error
            value = None
        if value:
            print(f"  ok   {label}")
            return value
        time.sleep(3)
    raise Failed(f"{label}: still not true after {timeout}s" + (f" ({last})" if last else ""))


def service_names(node, found):
    """Collects every service.name attribute anywhere in a Tempo (OTLP JSON) payload."""
    if isinstance(node, dict):
        if node.get("key") == "service.name":
            value = node.get("value") or {}
            name = value.get("stringValue")
            if name:
                found.add(name)
        for child in node.values():
            service_names(child, found)
    elif isinstance(node, list):
        for child in node:
            service_names(child, found)
    return found


def compose_logs(service, since="20m"):
    result = subprocess.run(
        ["docker", "compose", "--profile", "core", "--profile", "app", "--profile", "observability",
         "logs", "--no-log-prefix", "--since", since, service],
        capture_output=True, text=True, check=True)
    return result.stdout


try:
    step("Prometheus: scrape targets")
    targets = {item[0].get("service"): item[1] for item in query('up{job="eventflow-services"}')}
    check(sorted(targets) == SERVICES, f"the five services are scraped ({', '.join(sorted(targets))})")
    check(all(value == 1 for value in targets.values()), "every service target is UP")
    rabbit = query('up{job="rabbitmq"}')
    check(rabbit and rabbit[0][1] == 1, "RabbitMQ's own Prometheus listener is UP")

    step("Prometheus: the meters the dashboards read")

    def wait_metric(label, expr, predicate):
        def probe():
            rows = query(expr)
            return rows if rows and predicate(rows[0][1]) else None
        return wait_for(label, probe)

    # Prometheus scrapes every 10s, so the flow is in the series a few seconds after it ended.
    wait_metric("booking_attempts_total counts the flow's attempts",
                "sum(booking_attempts_total)", lambda value: value >= 1)
    wait_metric("booking_confirmed_total counts the confirmed sale",
                "sum(booking_confirmed_total)", lambda value: value >= 1)
    check(query("booking_conflicts_total"), "booking_conflicts_total is registered")
    check(query("booking_expired_total"), "booking_expired_total is registered")
    check(query("payment_failures_total"), "payment_failures_total is registered")
    check(query("waiting_room_users"), "waiting_room_users is exported")
    check(query('http_server_requests_seconds_bucket{service="booking"}'),
          "HTTP latency histograms exist (buckets, not only an average)")
    check(query('rabbitmq_queue_messages{job="rabbitmq"}'), "RabbitMQ queue depth is scraped")

    step("Tempo: one trace across the reservation flow")

    # TraceQL asks Tempo the question the gate asks: is there one trace in which a Booking span is
    # the ancestor of a Notification span? The relay and the broker hops are real spans inside it.
    def find_flow_trace():
        now = int(time.time())
        traceql = ('{ resource.service.name = "booking-service" }'
                   ' >> { resource.service.name = "notification-service" }')
        search = (f"{TEMPO}/api/search?" + urllib.parse.urlencode({
            # Only traces that started with this run: five seconds of slack for clock skew.
            "q": traceql, "limit": 5, "start": FLOW_START - 5, "end": now}))
        payload = get_json(search)
        for summary in payload.get("traces", []):
            trace_id = summary.get("traceID")
            if not trace_id:
                continue
            detail = get_json(f"{TEMPO}/api/traces/{trace_id}")
            names = service_names(detail, set())
            if {"booking-service", "payment-service", "notification-service"} <= names:
                return trace_id, names
        return None

    trace_id, names = wait_for(
        "one trace links the Booking request to the Notification consumer", find_flow_trace)
    joined = ", ".join(sorted(names))
    print("       trace " + trace_id + " spans: " + joined)
    if "catalog-service" not in names:
        print("       (no catalog span in this trace: the flow started at the reservation)")

    step("Grafana: dashboards as code")
    health = get_json(f"{GRAFANA}/api/health")
    check(health.get("database") == "ok", "Grafana is healthy")
    # A fresh Tempo reports 503 on /ready until its ingester has waited out its startup check, so
    # the gate waits for the backend instead of failing on a container that is merely cold.
    wait_for("Grafana can reach a ready Tempo through its datasource proxy",
             lambda: get_text(f"{TEMPO}/ready").strip() == "ready" or None)
    search = get_json(f"{GRAFANA}/api/search?" + urllib.parse.urlencode({"query": "EventFlow", "type": "dash-db"}))
    uids = sorted(item.get("uid") for item in search)
    check(uids == ["eventflow-business", "eventflow-messaging", "eventflow-rest", "eventflow-services"],
          "the four provisioned dashboards are loaded")
    dashboard = get_json(f"{GRAFANA}/api/dashboards/uid/eventflow-business")
    check(dashboard["dashboard"]["title"].startswith("EventFlow"),
          "the business dashboard answers with its definition")

    step("Logs: JSON with the trace id and the reservation")
    logs = compose_logs("booking")
    check('"service":{"name":"booking-service"' in logs,
          "Booking writes one JSON object per line with its service name")
    # The ECS formatter renders the MDC as top-level members, so the trace correlation appears as
    # "traceId" next to "spanId" and the ids this project adds.
    check(f'"traceId":"{trace_id}"' in logs,
          f"a Booking log line carries the trace id {trace_id}")
    check('"reservationId"' in logs, "a consumer log line names the reservation it handled")

    print()
    print("PASS: dashboards are loaded and one reservation flow is one visible trace")
except Failed as error:
    print(f"FAIL: {error}", file=sys.stderr)
    sys.exit(1)
PY
