#!/usr/bin/env bash
# Static guards for the Phase 7 observability contract (S1, S3, S4, S5, S6). No containers and no
# network: every failure here is an edit that would break the running stack or the phase gate.
#
#   ./scripts/observability-invariants-test.sh
set -euo pipefail

cd "$(dirname "$0")/.."

exec python3 - <<'PY'
import re
import sys

failures = 0
SERVICES = ["catalog", "booking", "notification", "payment", "payment-simulator"]


def read(path):
    with open(path) as handle:
        return handle.read()


def check(condition, message):
    global failures
    if condition:
        print(f"ok   {message}")
    else:
        print(f"FAIL {message}", file=sys.stderr)
        failures += 1


def service_block(name):
    text = read("compose.yaml")
    match = re.search(rf"^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)", text, re.S | re.M)
    return match.group(1) if match else ""


# S1: registry dependency, endpoint exposed next to health,info, and no published application port.
for svc in SERVICES:
    build = read(f"services/{svc}/build.gradle.kts")
    yml = read(f"services/{svc}/src/main/resources/application.yml")
    check("io.micrometer:micrometer-registry-prometheus" in build, f"{svc} ships the Prometheus registry")
    check("include: health,info,prometheus" in yml, f"{svc} exposes health,info,prometheus")
    check("ports:" not in service_block(svc), f"{svc} publishes no host port")

# S2/S5: the business meters exist in code and the latency histogram backing the dashboards is on.
for svc in SERVICES:
    yml = read(f"services/{svc}/src/main/resources/application.yml")
    check("percentiles-histogram" in yml and "http.server.requests: true" in yml,
          f"{svc} exports HTTP latency buckets")
for meter in ["booking.attempts", "booking.confirmed", "booking.conflicts", "booking.expired"]:
    check(meter in read("services/booking/src/main/java/com/eventflow/booking/shared/metrics/BookingMetrics.java"),
          f"Booking registers {meter}")
check("waiting.room.users" in read(
    "services/booking/src/main/java/com/eventflow/booking/waitingroom/WaitingRoomUsersGauge.java"),
    "Booking registers the waiting.room.users gauge")
check("payment.failures" in read(
    "services/payment/src/main/java/com/eventflow/payment/payment/PaymentMetrics.java"),
    "Payment registers payment.failures")

# S3/S4: Boot 4.2 property names only, AMQP observation on, ECS logs.
for svc in SERVICES:
    yml = read(f"services/{svc}/src/main/resources/application.yml")
    check("management.opentelemetry.tracing.export.otlp" in yml.replace("\n  ", "\n  ") or
          "endpoint: ${OTEL_TRACES_ENDPOINT" in yml,
          f"{svc} configures the OTLP tracing endpoint")
    check("management.otlp.tracing" not in yml, f"{svc} uses no deprecated Boot 3 OTLP property")
    check("type: W3C" in yml, f"{svc} propagates W3C")
    check(re.search(r"console: \$\{EVENTFLOW_LOG_FORMAT:ecs\}", yml) is not None,
          f"{svc} writes ECS JSON on stdout")
for svc, expected in [("catalog", 1), ("booking", 2), ("payment", 2)]:
    yml = read(f"services/{svc}/src/main/resources/application.yml")
    # Catalog only publishes, so it enables the template; Booking and Payment also consume.
    check(len(re.findall(r"observation-enabled: true", yml)) >= expected,
          f"{svc} enables observation on {expected} side(s)")
check("observation-enabled: true" in read("services/notification/src/main/resources/application.yml"),
      "notification enables listener observation")
for svc in ["catalog", "booking", "payment"]:
    migrations = f"services/{svc}/src/main/resources/db/migration"
    import glob
    check(bool(glob.glob(f"{migrations}/V*__outbox_trace_context.sql")),
          f"{svc} migrates the outbox traceparent column")
    entity = read(f"services/{svc}/src/main/java/com/eventflow/{svc}/shared/messaging/OutboxEvent.java")
    check("traceparent" in entity, f"{svc} stores the traceparent on the outbox row")

# S5: pinned images, loopback-only published ports, Tempo and the collector internal.
compose = read("compose.yaml")
for image in ["prom/prometheus:v3.16.0", "grafana/grafana:13.2.6", "grafana/tempo:2.10.10",
              "otel/opentelemetry-collector-contrib:0.165.0"]:
    check(f"image: {image}" in compose, f"{image} is pinned")
for name in ["prometheus", "grafana"]:
    check('"127.0.0.1:' in service_block(name), f"{name} publishes on loopback only")
for name in ["tempo", "otel-collector"]:
    check("ports:" not in service_block(name), f"{name} stays internal")

# S5/S6: the scrape targets, the dashboards as code and the gate's own guard.
prometheus = read("deploy/observability/prometheus/prometheus.yml")
for target in ["catalog:8081", "booking:8082", "notification:8083", "payment:8084",
               "payment-simulator:8085", "rabbitmq:15692", "localhost:9090"]:
    check(target in prometheus, f"prometheus scrapes {target}")
for uid in ["eventflow-services", "eventflow-rest", "eventflow-messaging", "eventflow-business"]:
    check(f'"uid": "{uid}"' in read(f"deploy/observability/grafana/dashboards/{uid}.json"),
          f"dashboard {uid} is code")
check('"editable": false' in read("deploy/observability/grafana/dashboards/eventflow-business.json"),
      "a provisioned dashboard cannot be edited in the UI")
check("allowUiUpdates: false" in read("deploy/observability/grafana/provisioning/dashboards/dashboards.yaml"),
      "the UI cannot save a provisioned dashboard")
check("uid: prometheus" in read("deploy/observability/grafana/provisioning/datasources/datasources.yaml"),
      "the Prometheus datasource has a fixed uid")
check("FLOW_START=$(date +%s)" in read("scripts/observability-verify.sh"),
      "the verify script records the run start")
check("FLOW_START - 5" in read("scripts/observability-verify.sh"),
      "the Tempo search is bounded by the run start (no stale trace can pass)")

if failures:
    print(f"FAIL: {failures} observability invariant(s) broken", file=sys.stderr)
    sys.exit(1)
print("PASS: observability invariants hold")
PY
