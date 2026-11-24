# EventFlow — Phase 7 (Observability: Prometheus, Grafana, OTel and Tempo)

- **Objective:** make one reservation flow observable end to end — metrics in Prometheus, four
  provisioned Grafana dashboards and a single trace that spans Booking, Payment and Notification —
  on the Compose stack. Gate from `docs/06-roadmap-calidad.md`: `Dashboard y traza visible de un
  flujo de reserva`.
- **Branch:** `feat/observability` (from `main` after PR #6, merge commit `c219ccf`).
- **Delivery strategy:** `ask-on-risk` (forecast ~1,800 authored lines across configs, dashboards,
  meters and scripts → work-unit commits; publishing stays a human decision). Scope decision L2:
  **Compose only** — k3d is untouched, so the cluster keeps exactly what Phase 6 proved.
- **Test runners:** Gradle (`./gradlew :booking:test`, `:payment:test`, `:catalog:test`,
  `:notification:test`, `:payment-simulator:test`) with Testcontainers; shell verification against
  the running Compose stack (`scripts/observability-verify.sh`, `curl` only, no `jq`).

## Specs

- **S1 — Prometheus endpoint in every service.** Each of the five services adds the BOM-managed
  `io.micrometer:micrometer-registry-prometheus` and exposes `prometheus` in
  `management.endpoints.web.exposure.include` (keeping `health,info`). `GET /actuator/prometheus`
  answers `200` with `text/plain;version=0.0.4` and contains JVM and HTTP server meters. No new
  published port: the endpoint stays on the Compose network, reachable only by Prometheus, and the
  front door remains the only published application port (Phase 6 invariant).
  - Test: `ActuatorProbeTest` (Catalog) and one `MetricsEndpointTest` per remaining service assert
    the status, the content type and a known meter (`jvm_memory_used_bytes`).
- **S2 — Business meters.** Booking owns `booking_attempts_total`, `booking_confirmed_total`,
  `booking_conflicts_total`, `booking_expired_total` and the `waiting_room_users` gauge; Payment
  owns `payment_failures_total`. Exact semantics, once each and only on the documented event:
  - `booking_attempts_total` — one increment per accepted creation attempt, after the idempotency
    claim succeeds and before the units are held (so a conflict still counts as an attempt, and an
    idempotent replay does not).
  - `booking_conflicts_total` — one increment per creation refused for insufficient tickets
    (the `409` path).
  - `booking_confirmed_total` — one increment per reservation confirmed from a payment result.
  - `booking_expired_total` — one increment per reservation expired by the expiry worker.
  - `payment_failures_total` — one increment per authorization that ends rejected, timed out or
    failed at the provider, tagged `reason` = `REJECTED`, `TIMEOUT` or `ERROR` (three values only).
  - `waiting_room_users` — gauge of queued users, summed over the `waiting-events` registry. It is
    read at scrape time, never on the reservation path, and a Redis failure keeps the last known
    value so the metric can never stall or fail a scrape.
  - No tag carries a userId, reservationId, eventId or any other high-cardinality value.
  - Test: one test per meter asserting the increment (or gauge value) after the real flow, and a
    test that a replay/invalid path does not increment.
- **S3 — Tracing across HTTP and AMQP, with outbox continuity.** Every service adds the
  BOM-managed `spring-boot-micrometer-tracing-opentelemetry` plus the runtime
  `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp`. Configuration uses the Boot
  4.2 names (verified in the 4.2.0 configuration metadata, not the Boot 3 ones):
  `management.opentelemetry.tracing.export.otlp.endpoint` (HTTP), `management.tracing.sampling.probability`
  = `1.0` for the local stack, `management.tracing.propagation.type` = `W3C`. AMQP context
  propagation requires `spring.rabbitmq.template.observation-enabled` and
  `spring.rabbitmq.listener.simple.observation-enabled` = `true` (both default to `false`);
  `spring-amqp 4.0.6`'s `RabbitMessageSenderContext`/`RabbitMessageReceiverContext` write and read
  the headers, so no extra instrumentation module is added.
  - **Outbox continuity:** a relay publishes seconds after the request that wrote the row, in
    another thread, so without help the broker hop starts an unrelated trace. Catalog, Booking and
    Payment persist the W3C `traceparent` of the span that wrote the outbox row (migration
    `V3__outbox_trace_context.sql` in Catalog and Payment, `V4__…` in Booking — the next free
    version in each service — nullable `traceparent varchar(100)`) and the relay restores it before
    the send, so the broker receives the same **trace id** with the relay hop as the new parent id
    (W3C advances the parent on every hop; the trace id is what must survive). A row without a
    `traceparent` (a scheduled job) publishes under the relay's own span, unchanged.
  - Test (Booking `OutboxTraceTest`): a checkout leaves a W3C-shaped `traceparent`
    (`00-<32 hex>-<16 hex>-<2 hex flags>`) on the outbox row, and the relay publishes the same
    trace id to a durable probe queue with the relay hop as the new parent; a row inserted with
    `traceparent = null` still publishes.
- **S4 — Structured JSON logs.** Every service sets `logging.structured.format.console: ecs`, so
  stdout is one JSON object per line carrying `service.name` (from `spring.application.name`),
  `trace.id`, `span.id` and the MDC. Booking's payment-result listener and Notification's consumer
  put `reservationId` and `eventId` in the MDC for the duration of the handler and clear them
  afterwards, so a log line ties a message to its reservation. No sensitive data is logged.
  - Test: a unit test of the MDC scope (present inside the handler, absent after, and absent when
    the handler throws); the JSON contract itself is proven end to end by S6 (a container log line
    parsed for `service.name` and `trace.id`).
- **S5 — Compose observability profile.** `compose.yaml` gains `prometheus`, `grafana`,
  `otel-collector` and `tempo` under profile `observability`, with configuration under
  `deploy/observability/`: `prometheus.yml` scrapes the five services' `/actuator/prometheus`,
  RabbitMQ's own Prometheus listener and Prometheus itself; `otel-collector.yaml` receives OTLP and
  batches it to Tempo; `tempo.yaml` uses local storage. Grafana is provisioned from files —
  datasources for Prometheus and Tempo plus a dashboard provider — so four dashboards are code, not
  clicking: (1) services and JVM, (2) REST latency and errors, (3) RabbitMQ, retries and DLQ,
  (4) reservations, expirations and the waiting room. Only Grafana (`${GRAFANA_PORT:-3000}`) and
  Prometheus (`${PROMETHEUS_PORT:-9090}`) publish a port, both on `127.0.0.1`; Tempo and the
  collector stay internal. Pinned image tags: `prom/prometheus:v3.16.0`, `grafana/grafana:13.2.6`,
  `grafana/tempo:2.10.10`, `otel/opentelemetry-collector-contrib:0.165.0` (verified on Docker Hub
  on 2026-11-24; re-checked before writing the manifests). RabbitMQ 4's management image already
  serves Prometheus on its own port `15692` — **verified in a container**: `:15672/metrics` is
  `404` while `:15692/metrics` is `200` — so the scrape target is `rabbitmq:15692` and no plugin
  change is needed.
- **S6 — Scripts and end-to-end proof.** `scripts/observability-up.sh` starts `core` + `app` +
  `observability` (with `COMPOSE_PARALLEL_LIMIT=1`, as the other stack scripts do), waits for
  health and prints the Grafana, Prometheus and front-door URLs; `scripts/observability-down.sh
  [--volumes]` stops it. `scripts/observability-verify.sh` runs the documented booking flow through
  the front door and then proves the gate without a UI click: a single Tempo trace id contains
  spans from Booking, Payment and Notification (queried through Tempo's HTTP API), the same
  trace id appears in Booking's log line, the `booking_*`, `payment_failures_total` and
  `waiting_room_users` series are present in Prometheus, and the four dashboards answer through
  Grafana's API.
- **S7 — Documentation.** `docs/05-infra-observabilidad.md` records what Phase 7 implements
  (endpoints, commands, what each dashboard answers, what is deliberately out of scope);
  `docs/adr/0014-observability.md` records the decisions (Prometheus scrape instead of OTLP
  metrics, the per-service registry, outbox `traceparent` and why a relay breaks causality,
  ECS structured logging with no new dependency, sampling 1.0 as a local-only default, Grafana as
  code, RabbitMQ's 15692 listener); `README.md` gains the observability commands; `.env.example`
  documents the two new ports. No claim of measured coverage or production readiness.

## Tasks

- **T1 — Prometheus endpoint in the five services** (S1). Route: inline. Done: `e7813d0`.
- **T2 — Business meters and their tests** (S2). Route: inline. Done: `0b70b79`.
- **T3 — Tracing deps, HTTP/AMQP observation, outbox traceparent** (S3). Route: inline.
  Done: `4d5d75d`.
- **T4 — ECS structured logs and MDC ids** (S4). Route: inline. Done: see `git log` `feat(logs)`.
- **T5 — Observability profile, configs and dashboards** (S5). Route: inline.
- **T6 — Stack scripts and end-to-end verification** (S6). Route: inline.
- **T7 — Docs, ADR and independent verification** (S7). Route: inline with an independent verifier.

## Log

- **L1 (2026-11-24, user, verbatim):** "let's implement next phase"
- **L2 (2026-11-24, user, choice on the scope block):** "Compose only (recommended)" — the
  observability stack runs as a Compose profile; k3d keeps exactly what Phase 6 delivered.
- **L3 (2026-11-24, decision):** phase row read from `docs/06-roadmap-calidad.md` (Fase 7,
  Observabilidad: Prometheus, Grafana, OTEL y Tempo; gate `Dashboard y traza visible de un flujo de
  reserva`). The stack follows `docs/05-infra-observabilidad.md` literally: Micrometer +
  Prometheus Registry scraped by Prometheus, and Micrometer Tracing + OTLP → Collector → Tempo.
- **L4 (2026-11-24, evidence):** verified from `spring-boot-dependencies-4.2.0.pom` that
  `spring-boot-starter-opentelemetry`, `spring-boot-micrometer-tracing-opentelemetry`,
  `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel` and
  `opentelemetry-exporter-otlp` are all BOM-managed (micrometer 1.18.0, micrometer-tracing 1.8.0,
  opentelemetry 1.68.0, prometheus-client 1.10.0). The OTLP tracing properties were read from
  `spring-boot-micrometer-tracing-opentelemetry-4.2.0.jar`'s configuration metadata:
  `management.opentelemetry.tracing.export.otlp.endpoint` and
  `management.tracing.export.otlp.enabled`; the Boot 3 spelling `management.otlp.tracing.endpoint`
  is present in 4.2 only as a deprecated alias.
- **L5 (2026-11-24, evidence):** `spring.rabbitmq.template.observation-enabled` and
  `spring.rabbitmq.listener.simple.observation-enabled` default to `false` in
  `spring-boot-rabbitmq-4.2.0`'s metadata. `spring-rabbit 4.0.6` sources show
  `RabbitMessageSenderContext` writing headers through
  `message.getMessageProperties().setHeader(key, value)` and `RabbitMessageReceiverContext` reading
  them, so enabling observation is enough for `traceparent` to travel with the message.
- **L6 (2026-11-24, evidence):** RabbitMQ 4's management image serves the Prometheus plugin on its
  own HTTP listener. Proven in a container: `/metrics` on the management port `15672` is `404`,
  while `http://localhost:15692/metrics` is `200` and returns `telemetry_scrape_size_bytes`. The
  plugin needs no extra file or command; the default image already enables it.
- **L7 (2026-11-24, evidence T1):** RED observed first on the cheapest service: with
  `micrometer-registry-prometheus` removed, `MetricsEndpointTest` (payment-simulator) failed on the
  missing endpoint; GREEN once restored. `:payment-simulator:test` and `:catalog:test` green, then
  `:booking:test`, `:notification:test` and `:payment:test` green (`MetricsEndpointTest` in each).
  Two test-only findings: Booking's `BookingIntegrationTest` holds containers only, so a new class
  must declare `@SpringBootTest(properties = …)` itself (mirrored `ReservationApiIntegrationTest`
  so the context is the cached one), and `:payment` had no `spring-boot-starter-webmvc-test`, added
  as test scope for MockMvc.
- **L8 (2026-11-24, evidence T2):** `BookingMetricsTest` pins the semantics as deltas (counters live
  for the whole JVM): one attempt per new creation, none for a replay, one attempt plus one conflict
  for a refused hold. Insufficient stock answers **422**, not 409 (the handler maps
  `InsufficientTicketsException` to `Unprocessable Content`), so the test asserts
  `isUnprocessableContent`. Expiry, confirmation, waiting-room gauge and the two payment failure
  reasons are asserted inside the existing integration tests that already drive those flows.
  `PaymentMetrics` registers its three `reason` series at startup: a lazily created counter made
  `payment_failures_total` absent from the scrape until the first failure, which a dashboard would
  render as "no data" instead of zero.
- **L9 (2026-11-24, evidence T3):** Boot 4.2's tracing auto-configuration is split into modules, so
  the service needs `spring-boot-micrometer-tracing-opentelemetry` (auto-configuration) plus the
  runtime `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp`; the starter was not
  used because it also adds `micrometer-registry-otlp`, which would push a second copy of every
  metric while Prometheus scrapes them. `OTEL_TRACES_ENABLED` defaults to `false` so a `bootRun` or
  a test run creates and propagates spans without posting them to a collector that does not exist.
- **L10 (2026-11-24, defect found and fixed by an existing test):** enabling tracing broke
  `CatalogApiIntegrationTest.echoesRequestIdInHeaderAndProblemDetails` and
  `ignoresUnsafeRequestId`: Micrometer's correlation decorator writes `traceId` into the MDC, so the
  `X-Request-Id` value this project put under `traceId` was overwritten by the OpenTelemetry trace
  id. The MDC key is now `requestId` (`TraceId.MDC_KEY`), the public contract is unchanged (`409`
  bodies still echo the caller's id as `traceId`) and log correlation gains the real trace id.
- **L11 (2026-11-24, API facts verified in the jar, not assumed):** `micrometer-tracing 1.8.0` has
  no `io.micrometer.tracing.Scope`/`SpanContext` types: `Span.context()` returns `TraceContext`,
  `Propagator.inject` takes a `TraceContext`, `Propagator.extract` returns a `Span.Builder`, and a
  scope is `Tracer.SpanInScope` (Closeable). The relay therefore extracts, starts a span named
  `outbox relay` as the child of the stored context and ends it after the send. Observed in the
  first RED of `OutboxTraceTest`: the broker got the same trace id with a new parent id (`03` flags
  = sampled + random trace id), which is why the assertion compares trace ids and not whole
  headers.
- **L12 (2026-11-24, evidence T4):** `logging.structured.format.console=ecs` is Boot 4.2's own
  structured logging, so no encoder dependency was added. Verified on a real `bootRun` of the
  simulator: the startup line is one JSON object with `"service":{"name":"payment-simulator-service"}`,
  `@timestamp`, `ecs.version` and no stack of plain text. `EventLogContext` (Booking and
  Notification, `shared/messaging`) puts `eventId`/`reservationId` in the MDC around the consumer
  effect and removes them on close, including when the handler throws; three unit tests per service
  pin the present/absent/cleared cases. The `trace.id` half of the contract is proven end to end in
  S6, because a unit test cannot honestly assert what the ECS encoder writes for an active span.
