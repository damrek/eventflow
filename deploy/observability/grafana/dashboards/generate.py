#!/usr/bin/env python3
"""Generates the four provisioned Grafana dashboards of the EventFlow stack.

The dashboards are code: this script keeps them consistent (same layout, same datasource uids) and
is the place to change a panel. Run it from the repository root and commit the JSON it writes:

    python3 deploy/observability/grafana/dashboards/generate.py

Metric names are the ones Prometheus actually scrapes (verified against a live scrape of the five
services and of RabbitMQ 4's Prometheus listener), not guesses from documentation.
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROM = {"type": "prometheus", "uid": "prometheus"}


def timeseries(pid, title, targets, x, y, unit="", height=8, width=12, description=""):
    return {
        "id": pid,
        "type": "timeseries",
        "title": title,
        "description": description,
        "datasource": PROM,
        "gridPos": {"h": height, "w": width, "x": x, "y": y},
        "targets": [
            {"refId": chr(ord("A") + i), "datasource": PROM, "expr": expr, "legendFormat": legend}
            for i, (expr, legend) in enumerate(targets)
        ],
        "fieldConfig": {
            "defaults": {"unit": unit, "custom": {"lineWidth": 1, "fillOpacity": 8}},
            "overrides": [],
        },
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
    }


def stat(pid, title, targets, x, y, unit="", description="", width=6):
    return {
        "id": pid,
        "type": "stat",
        "title": title,
        "description": description,
        "datasource": PROM,
        "gridPos": {"h": 6, "w": width, "x": x, "y": y},
        "targets": [
            {"refId": chr(ord("A") + i), "datasource": PROM, "expr": expr, "legendFormat": legend}
            for i, (expr, legend) in enumerate(targets)
        ],
        "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
        "options": {
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "colorMode": "value",
            "graphMode": "area",
            "textMode": "auto",
        },
    }


def dashboard(uid, title, panels, description):
    return {
        "uid": uid,
        "title": title,
        "description": description,
        "tags": ["eventflow"],
        "timezone": "browser",
        # Read-only in the UI: a change belongs in this script and a commit (A3 of the phase 7
        # verification: the provisioning flag stops saving, this stops editing).
        "editable": False,
        "schemaVersion": 39,
        "version": 1,
        "refresh": "10s",
        "time": {"from": "now-30m", "to": "now"},
        "panels": panels,
    }


SERVICES = 'job="eventflow-services"'

services_and_jvm = dashboard(
    "eventflow-services",
    "EventFlow — Services and JVM",
    [
        stat(1, "Targets up", [(f'up{{{SERVICES}}}', "{{service}}")], 0, 0,
             description="1 means Prometheus can scrape that service.", width=24),
        timeseries(2, "Heap used", [(f'jvm_memory_used_bytes{{{SERVICES},area="heap"}}', "{{service}}")],
                   0, 6, unit="bytes"),
        timeseries(3, "Process CPU", [(f'process_cpu_usage{{{SERVICES}}}', "{{service}}")],
                   12, 6, unit="percentunit"),
        timeseries(4, "Live threads", [(f'jvm_threads_live_threads{{{SERVICES}}}', "{{service}}")],
                   0, 14),
        timeseries(5, "GC pauses per second",
                   [(f'sum by (service) (rate(jvm_gc_pause_seconds_count{{{SERVICES}}}[$__rate_interval]))',
                     "{{service}}")], 12, 14),
        timeseries(6, "Database pool in use",
                   [(f'hikaricp_connections_active{{{SERVICES}}}', "{{service}}")],
                   0, 22,
                   description="Payment and Catalog and Booking and Notification own a pool; the "
                               "simulator has none, so it is simply absent."),
        timeseries(7, "Readiness",
                   [(f'max by (service) (up{{{SERVICES}}})', "{{service}}")], 12, 22,
                   description="A scrape target answers when the process answers; readiness is the "
                               "health group the platform uses."),
    ],
    "One row per service: is it up, how much memory and CPU does it use, what does the JVM do, and "
    "is the database pool healthy.",
)

rest = dashboard(
    "eventflow-rest",
    "EventFlow — REST latency and errors",
    [
        stat(1, "5xx ratio (5m)",
             [(f'(sum(rate(http_server_requests_seconds_count{{{SERVICES},status=~"5.."}}'
               f'[$__rate_interval])) or vector(0))'
               f' / clamp_min(sum(rate(http_server_requests_seconds_count{{{SERVICES}}}[$__rate_interval])), 1e-9)',
               "5xx")], 0, 0, unit="percentunit", width=8,
             description="or vector(0) keeps the panel at zero instead of \"no data\" while no "
                         "request fails."),
        stat(2, "Requests per second (5m)",
             [(f'sum(rate(http_server_requests_seconds_count{{{SERVICES}}}[$__rate_interval]))', "all")],
             8, 0, unit="reqps", width=8),
        stat(3, "p95 latency (5m)",
             [(f'histogram_quantile(0.95, sum by (le) '
               f'(rate(http_server_requests_seconds_bucket{{{SERVICES}}}[$__rate_interval])))', "p95")],
             16, 0, unit="s", width=8),
        timeseries(4, "Latency p50 / p95 / p99",
                   [(f'histogram_quantile(0.50, sum by (le, service) '
                     f'(rate(http_server_requests_seconds_bucket{{{SERVICES}}}[$__rate_interval])))',
                     "{{service}} p50"),
                    (f'histogram_quantile(0.95, sum by (le, service) '
                     f'(rate(http_server_requests_seconds_bucket{{{SERVICES}}}[$__rate_interval])))',
                     "{{service}} p95"),
                    (f'histogram_quantile(0.99, sum by (le, service) '
                     f'(rate(http_server_requests_seconds_bucket{{{SERVICES}}}[$__rate_interval])))',
                     "{{service}} p99")], 0, 6, unit="s",
                   description="Buckets come from management.metrics.distribution.percentiles-histogram."),
        timeseries(5, "Requests per second by service",
                   [(f'sum by (service) (rate(http_server_requests_seconds_count{{{SERVICES}}}[$__rate_interval]))',
                     "{{service}}")], 0, 14, unit="reqps"),
        timeseries(6, "Errors by status",
                   [(f'sum by (service, status) '
                     f'(rate(http_server_requests_seconds_count{{{SERVICES},status=~"[45].."}}[$__rate_interval]))',
                     "{{service}} {{status}}")], 12, 14),
    ],
    "What the API answers: throughput, the latency percentiles a client feels, and which status "
    "codes produce the failures.",
)

messaging = dashboard(
    "eventflow-messaging",
    "EventFlow — Messaging, retries and DLQ",
    [
        timeseries(1, "Messages in queues",
                   [('rabbitmq_detailed_queue_messages{job="rabbitmq-queues"}', "{{queue}}")],
                   0, 0,
                   description="Ready + unacknowledged, per queue. A growing main queue is a slow "
                               "consumer."),
        timeseries(2, "Ready to deliver",
                   [('rabbitmq_detailed_queue_messages_ready{job="rabbitmq-queues"}', "{{queue}}")],
                   12, 0),
        timeseries(3, "Consumers",
                   [('rabbitmq_detailed_queue_consumers{job="rabbitmq-queues"}', "{{queue}}")],
                   0, 8,
                   description="Zero on a queue this stack consumes means nobody is listening."),
        timeseries(4, "Published per second",
                   [('rate(rabbitmq_queue_messages_published_total{job="rabbitmq"}[$__rate_interval])',
                     "published")], 12, 8, unit="ops",
                   description="The broker aggregates this one; per-queue detail is in the depth "
                               "panels."),
        timeseries(5, "Retry and dead-letter queues",
                   [('rabbitmq_detailed_queue_messages{job="rabbitmq-queues",'
                     # [.] instead of \\. so the expression needs no escape at all.
                     'queue=~".*(retry[.][0-9]+|dlq)$"}', "{{queue}}")],
                   0, 16,
                   description="A message in a retry queue is waiting for its backoff; one in a DLQ "
                               "needs a human."),
        timeseries(6, "Redeliveries per second",
                   [('rate(rabbitmq_queue_messages_redelivered_total{job="rabbitmq"}[$__rate_interval])',
                     "redelivered")], 12, 16,
                   description="A redelivery is at-least-once doing its job, or a consumer failing."),
    ],
    "RabbitMQ as the flow sees it: queue depth, consumers, throughput, retries and the dead-letter "
    "queues that must stay empty in a healthy stack.",
)

business = dashboard(
    "eventflow-business",
    "EventFlow — Reservations and waiting room",
    [
        stat(1, "Attempts (15m)", [("sum(increase(booking_attempts_total[15m]))", "attempts")],
             0, 0, width=8),
        stat(2, "Confirmed (15m)", [("sum(increase(booking_confirmed_total[15m]))", "confirmed")],
             8, 0, width=8),
        stat(3, "Conflicts (15m)", [("sum(increase(booking_conflicts_total[15m]))", "conflicts")],
             16, 0, width=8,
             description="Creations refused because the stock could not be held."),
        stat(4, "Waiting users", [("sum(waiting_room_users)", "queued")], 0, 6, width=8,
             description="Queue backlog read from Redis at scrape time; keeps its last value when "
                         "Redis is down."),
        stat(5, "Expired (15m)", [("sum(increase(booking_expired_total[15m]))", "expired")],
             8, 6, width=8),
        stat(6, "Payment failures (15m)",
             [('sum by (reason) (increase(payment_failures_total[15m]))', "{{reason}}")],
             16, 6, width=8,
             description="REJECTED is a settled refund path; TIMEOUT and ERROR count failed attempts, "
                         "including broker retries."),
        timeseries(7, "Attempts vs confirmations per second",
                   [("sum(rate(booking_attempts_total[$__rate_interval]))", "attempts"),
                    ("sum(rate(booking_confirmed_total[$__rate_interval]))", "confirmed")], 0, 12,
                   unit="ops"),
        timeseries(8, "Conflicts and expirations per second",
                   [("sum(rate(booking_conflicts_total[$__rate_interval]))", "conflicts"),
                    ("sum(rate(booking_expired_total[$__rate_interval]))", "expired")], 12, 12,
                   unit="ops"),
    ],
    "The business reading of the same traffic: how many reservations start, how many end confirmed, "
    "what the waiting room holds and how the provider failure modes split.",
)

for dashboard_json in (services_and_jvm, rest, messaging, business):
    path = HERE / f"{dashboard_json['uid']}.json"
    path.write_text(json.dumps(dashboard_json, indent=2) + "\n")
    print("wrote", path.name)
