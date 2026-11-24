# ADR 0014 — Metrics, traces, logs and dashboards as code

**Status:** Accepted (Phase 7)

## Context

Phase 7 is the observability row of `docs/06-roadmap-calidad.md`: `Prometheus, Grafana, OTEL y
Tempo`, with the quality gate `Dashboard y traza visible de un flujo de reserva`.
`docs/05-infra-observabilidad.md` fixes the shape: Actuator + Micrometer + Prometheus Registry with
`/actuator/prometheus` reachable on the internal network only; technical metrics (latency, errors,
JVM, database connections, consumers and queue depth) and business metrics
(`booking_attempts_total`, `booking_confirmed_total`, `booking_conflicts_total`,
`booking_expired_total`, `payment_failures_total`, `waiting_room_users`) without high-cardinality
labels; four Grafana dashboards; Micrometer Tracing + OpenTelemetry over OTLP to a collector and
Tempo, with the context propagated over HTTP and RabbitMQ metadata; JSON logs on stdout with
`traceId`, `spanId`, `service`, `eventId` and `reservationId` and no sensitive data.

Two facts about the stack pushed back on a straight reading of that document, and both were verified
before deciding rather than assumed:

- **Boot 4 split tracing auto-configuration into its own modules.** `spring-boot-starter-opentelemetry`
  brings the auto-configuration, the OpenTelemetry bridge, the OTLP span exporter *and*
  `micrometer-registry-otlp`. Using it would push a second copy of every metric by OTLP while
  Prometheus scrapes the same meters.
- **RabbitMQ 4 serves the Prometheus plugin on its own listener.** In the management image,
  `/metrics` on the management port `15672` answers `404`, while `http://…:15692/metrics` answers
  `200` with `rabbitmq_queue_messages` and friends. The plugin is enabled in the image; no file,
  command or plugin step is needed — only the right port.

Phase 6 already ships the same services as containers in Compose and in k3d. Adding the observability
stack to both would double the infrastructure for one phase, and `docs/05` itself says to run full
observability and k3d separately because of the resource budget. The phase therefore lands on
Compose only; k3d keeps exactly what Phase 6 proved.

## Decision

1. **Metrics by scrape, not by push.** Every service adds `micrometer-registry-prometheus` (BOM
   managed) and exposes `prometheus` next to `health,info`. Prometheus scrapes five
   `/actuator/prometheus` targets, RabbitMQ on `15692`, and itself. No service publishes a port; the
   front door stays the only published application port.
2. **Tracing with explicit modules, export opt-in.** Each service declares
   `spring-boot-micrometer-tracing-opentelemetry` plus the runtime `micrometer-tracing-bridge-otel`
   and `opentelemetry-exporter-otlp`, configured with the Boot 4.2 names
   (`management.opentelemetry.tracing.export.otlp.endpoint`, not the Boot 3
   `management.otlp.tracing.endpoint`). Export is off by default (`OTEL_TRACES_ENABLED`), so a
   `bootRun` or the test suite creates and propagates spans without posting them to a collector that
   does not exist; `scripts/observability-up.sh` turns it on. Sampling is `1.0` **for the local
   stack only** — a production deployment would lower it and say so.
3. **AMQP propagation is enabled, not reinvented.** `spring.rabbitmq.template.observation-enabled`
   and `spring.rabbitmq.listener.simple.observation-enabled` are both `true` (they default to
   `false`). `spring-rabbit 4.0.6`'s `RabbitMessageSenderContext`/`RabbitMessageReceiverContext`
   write and read `traceparent` through `MessageProperties`, so no instrumentation module is added
   and no header is built by hand.
4. **The outbox carries the trace.** The relay publishes seconds after the transaction that wrote
   the row, on a scheduled thread. Without help the broker hop would start an unrelated trace. Each
   producer's `outbox_event` gains a nullable `traceparent varchar(100)` column, written with the W3C
   header of the span that created the event; the relay extracts it, starts one span named
   `outbox relay` as its child and ends it after the send. A row with no `traceparent` (a scheduled
   job) publishes under the relay's own span, unchanged. W3C advances the parent id on every hop, so
   what must survive is the **trace id** — the test asserts exactly that.
5. **Structured logs with no new dependency.** `logging.structured.format.console: ecs` is Boot
   4.2's own encoder: one JSON object per line with `service.name`, `trace.id`, `span.id` and the
   MDC. Booking's payment-result listener and Notification's consumer put `eventId` and
   `reservationId` in the MDC for the duration of the effect and remove them afterwards, including
   when the handler throws.
6. **The request correlation id keeps its own MDC key.** Micrometer's correlation decorator owns
   `traceId` and `spanId` in the MDC, so the caller-provided `X-Request-Id` this project echoes in
   error bodies now lives under `requestId`. The public contract is unchanged; the two ids no longer
   overwrite each other.
7. **The business meters mean what the dashboard says.** Counters are in memory, not transactional:
   an attempt that rolls back still counts, which is the point of `booking_attempts_total` and
   `booking_conflicts_total`. `booking_confirmed_total` counts confirmations, `booking_expired_total`
   counts expirations, and `payment_failures_total` counts failed authorizations tagged `reason` ∈
   `REJECTED | TIMEOUT | ERROR`. `TIMEOUT`/`ERROR` count **attempts**, including broker retries of a
   transient provider failure — that is what the saga does, and the dashboard says it. The
   `waiting_room_users` gauge reads the `waiting-events` registry at scrape time, never on the
   reservation path, and returns its last successful reading while Redis is down. No tag carries a
   user, reservation or event id.
8. **Latency is a distribution, not an average.** `management.metrics.distribution.percentiles-histogram.http.server.requests`
   is `true` in every service, because without buckets there is no `_bucket` series and no
   `histogram_quantile` for the dashboards to draw.
9. **Dashboards are code.** `deploy/observability/grafana/provisioning` points at JSON files in
   `deploy/observability/grafana/dashboards`, generated by `generate.py` so layout and datasource
   uids stay consistent; the datasources are provisioned with fixed uids (`prometheus`, `tempo`), and
   the UI cannot edit a dashboard (`allowUiUpdates: false`). The four dashboards answer the four
   questions the document asks: services and JVM, REST latency and errors, messaging with retries and
   DLQ, and the business view (attempts, confirmations, conflicts, expirations, waiting users and
   provider failures).
10. **Tempo and the collector stay internal.** Tempo is a single binary with local block storage in a
    volume and anonymous usage reporting disabled; the collector receives OTLP and batches into
    Tempo. Only Grafana (`GRAFANA_PORT`) and Prometheus (`PROMETHEUS_PORT`) publish a port, on
    `127.0.0.1`. Verification queries Tempo **through Grafana's datasource proxy**, which both proves
    the wiring and keeps Tempo unpublished.
11. **Verification is a script, not a screenshot.** `scripts/observability-verify.sh` runs the
    documented flow and then asserts, against the running stack: five service targets and RabbitMQ
    are up, the business meters and the HTTP histogram exist, one Tempo trace contains Booking,
    Payment and Notification spans, the four dashboards are loaded, and Booking's JSON log line
    carries that same trace id and the reservation id.

## Consequences

- **The phase gate is reproducible.** Anyone can run `scripts/observability-up.sh` and
  `scripts/observability-verify.sh` and see the same green result; no dashboard is clicked into
  existence and no trace id is pasted by hand.
- **The trace of a booking is one trace.** It starts at the HTTP request, crosses the outbox, the
  broker, Payment, the result event and Notification, and the relay shows up as its own span. The
  cost is one column per producer's `outbox_event` and one small span per published event.
- **A provider timeout is visible as attempts, not reservations.** `payment_failures_total{reason="TIMEOUT"}`
  counts what the saga tried; the reservation ends in `PAYMENT_FAILED` or the DLQ. The dashboard
  states this so the number is not misread.
- **Logs are JSON everywhere.** A human reading `bootRun` output sees JSON lines; the same lines are
  what a log pipeline would ingest, which is the point. `EVENTFLOW_LOG_FORMAT` can select another
  Boot format.
- **k3d stays without observability** in this phase. Manifests for Prometheus, Grafana, the
  collector and Tempo, and a second verification path against the Ingress, remain for a later
  iteration; nothing in this phase changes what `scripts/k3d-*.sh` does.
- **Out of scope, deliberately:** Loki (logs are queried with `docker compose logs`), alerting rules,
  SLOs, exemplars, tail-based sampling and a retention policy beyond six hours of Prometheus and a
  local Tempo volume.
