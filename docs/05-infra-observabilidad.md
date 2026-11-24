# 05 — Infraestructura y observabilidad

## Desarrollo local

Docker Compose levanta PostgreSQL, RabbitMQ, Redis, simulador de pagos y los servicios implementados. Perfiles separados: `core` (dependencias), `app` y `observability`. Healthchecks y volúmenes persistentes para servicios stateful. Proporcionar `.env.example`; no guardar secretos reales.

Cada servicio debe tener Dockerfile multi-stage, ejecutar como usuario no root y exponer Actuator health. Configuración por variables de entorno. El frontend se sirve detrás de un reverse proxy sencillo.

**Implementado (Fase 6, ADR 0013).** `docker/service.Dockerfile` es multi-stage y se parametriza
con `ARG SERVICE`; la etapa de ejecución usa `-jre-alpine`, corre como uid `10001` y expone
`/actuator/health`. `frontend/Dockerfile` compila la SPA una vez y ofrece dos destinos: `runtime-proxy`
(Compose, nginx con `envsubst`) y `runtime-static` (k3d, nginx sin privilegios). Perfiles de
`compose.yaml`: `core` (PostgreSQL, RabbitMQ, Redis) y `app` (los cinco servicios y la SPA con
`APP_PORT`, por defecto `8080`, como único puerto publicado). Comandos:
`./scripts/stack-up.sh`, `./scripts/stack-verify.sh` (flujo completo por la puerta de entrada) y
`./scripts/stack-down.sh [--volumes]`.

## Kubernetes local

Desplegar en k3d después de estabilizar Compose. Recursos mínimos:
- Deployment + Service por API.
- ConfigMap y Secret para configuración.
- Ingress para entrada HTTP.
- Readiness y liveness probes diferenciadas.
- Requests/limits de CPU y memoria.
- PVC para PostgreSQL/RabbitMQ/Redis si corren dentro del cluster.
- Una réplica inicial; escalar Booking a 2 para probar concurrencia.

No incluir Helm, ArgoCD, Istio ni cluster remoto en el MVP. Se permite mantener dependencias stateful en Compose al principio y migrarlas a k3d cuando sea razonable.

**Implementado (Fase 6, ADR 0013).** `deploy/k8s/` es un proyecto kustomize (`kubectl apply -k
deploy/k8s`) con namespace, ConfigMap, Secret creado desde `.env` por `scripts/k3d-up.sh`
(`eventflow-secrets`, nunca versionado), PostgreSQL y RabbitMQ como StatefulSet con PVC, Redis como
Deployment sin volumen (ADR 0011), Deployment + Service por API, frontend tras un único Ingress y
tres `Middleware` de Traefik que quitan `/api/catalog`, `/api/booking` y `/api/notification`. Booking
corre con `replicas: 2`; el resto con una. Sondas: `startupProbe` y `livenessProbe` sobre
`/actuator/health/liveness` (nunca dependen de un sistema externo) y `readinessProbe` sobre
`/actuator/health/readiness`, que incluye `db`. Comandos: `./scripts/k3d-up.sh`,
`./scripts/k3d-verify.sh`, `./scripts/k3d-down.sh [--delete-cluster]`. El Ingress responde en
`http://localhost:8090` para convivir con Compose.

## Proveedor de pagos simulado

WireMock o servicio local equivalente con respuestas configurables: aprobado, rechazado, timeout y error 5xx. Payment Service llama por HTTP con timeout explícito, clave de idempotencia y circuit breaker. El simulador nunca almacena datos de tarjeta.

## Métricas

Actuator + Micrometer + Prometheus Registry. Exponer `/actuator/prometheus` solo en red interna.

Métricas técnicas: latencia y tasa de peticiones, errores, JVM, conexiones DB, consumidores y profundidad de colas RabbitMQ.

Métricas de negocio: `booking_attempts_total`, `booking_confirmed_total`, `booking_conflicts_total`, `booking_expired_total`, `payment_failures_total`, `waiting_room_users`. Evitar etiquetas con userId, reservationId u otros valores de alta cardinalidad.

**Implementado (Fase 7, ADR 0014).** Los cinco servicios añaden `micrometer-registry-prometheus` y
exponen `prometheus` junto a `health,info`; ningún servicio publica un puerto, y Prometheus los
raspa por la red interna. `management.metrics.distribution.percentiles-histogram.http.server.requests`
está activo porque sin cubos no hay `histogram_quantile` y la latencia solo podría mostrarse como
media. Los medidores de negocio y su semántica: `booking_attempts_total` cuenta intentos de creación
que reclaman una clave de idempotencia (un reintento idempotente no cuenta),
`booking_conflicts_total` las creaciones rechazadas por falta de stock, `booking_confirmed_total` las
confirmaciones, `booking_expired_total` las expiraciones, `payment_failures_total` las autorizaciones
fallidas con la etiqueta `reason` ∈ `REJECTED | TIMEOUT | ERROR` (los dos últimos cuentan intentos,
incluidos los reintentos del broker) y `waiting_room_users` es un gauge que lee el registro
`waiting-events` en el momento del scrape y conserva su última lectura si Redis no responde. Ninguna
etiqueta lleva userId, reservationId ni eventId. RabbitMQ publica sus métricas en su propio listener
`:15692` (`/metrics` agregado y `/metrics/detailed` por cola); el puerto de management `:15672` no
sirve `/metrics`.

## Dashboards

Grafana: (1) salud y JVM de servicios; (2) latencia/errores REST; (3) RabbitMQ, retries y DLQ; (4) reservas, expiraciones y sala de espera.

**Implementado (Fase 7, ADR 0014).** Los cuatro dashboards son código:
`deploy/observability/grafana/dashboards/*.json`, generados por `generate.py` y aprovisionados por
Grafana con uid fijo (`eventflow-services`, `eventflow-rest`, `eventflow-messaging`,
`eventflow-business`). Las fuentes de datos se aprovisionan con uid `prometheus` y `tempo` y la UI no
permite editar un dashboard: lo que el repositorio contiene es lo que el stack muestra. Las
consultas usan los nombres que Prometheus raspa de verdad (verificados contra un scrape real), no
los de la documentación.

## Trazas y logs

Micrometer Tracing + OpenTelemetry OTLP → Collector → Tempo. Propagar contexto en HTTP y metadatos RabbitMQ. Logs JSON a stdout con `traceId`, `spanId`, `service`, `eventId` y `reservationId`; no incluir datos sensibles. Para el MVP consultar logs con Docker/kubectl; Loki queda como extensión.

**Implementado (Fase 7, ADR 0014).** Cada servicio declara
`spring-boot-micrometer-tracing-opentelemetry` más `micrometer-tracing-bridge-otel` y
`opentelemetry-exporter-otlp` (módulos del BOM, no el starter, que añadiría un segundo registro de
métricas por OTLP). La configuración usa los nombres de Boot 4.2
(`management.opentelemetry.tracing.export.otlp.endpoint`) y la exportación está apagada por defecto
(`OTEL_TRACES_ENABLED`): un `bootRun` o los tests crean y propagan spans sin publicarlos a un
colector que no existe. `spring.rabbitmq.template.observation-enabled` y
`spring.rabbitmq.listener.simple.observation-enabled` están activos, y el sender/receiver de
spring-amqp escribe y lee `traceparent` en las propiedades del mensaje. Como el relay del outbox
publica en otro hilo segundos después, cada `outbox_event` guarda el `traceparent` W3C de la
transacción que lo escribió y el relay lo restaura antes del envío: el resultado es **una** traza
desde el checkout hasta la notificación, con el hop del relay como span propio. En logs,
`logging.structured.format.console: ecs` escribe una línea JSON por evento con `service.name`,
`traceId`, `spanId` y el MDC; los consumidores añaden `eventId` y `reservationId` mientras dura el
efecto. El `X-Request-Id` de la API vive en el MDC como `requestId` porque Micrometer ya usa
`traceId`.

## Carga con k6

Escenarios: navegación del catálogo, 500 usuarios compitiendo por 100 entradas, tasa de llegada a waiting room y degradación del simulador de pago. Medir throughput, p50/p95/p99, tasa de error, conflictos y consistencia del inventario. Guardar scripts y resultados; no inventar cifras ni prometer umbrales antes de medir.

## Restricción de recursos

Con 32 GB RAM, arrancar solo perfil core durante desarrollo. Activar observabilidad completa y k3d por separado; limitar memoria/CPU de contenedores y no ejecutar simultáneamente todas las réplicas y escenarios de carga.

**Implementado (Fase 7).** El stack de observabilidad es el perfil `observability` de Compose
(Prometheus, Grafana, colector OTel y Tempo), con Prometheus y Grafana publicados solo en
`127.0.0.1` y Tempo y el colector dentro de la red. Comandos: `./scripts/observability-up.sh`,
`./scripts/observability-verify.sh` y `./scripts/observability-down.sh [--volumes]`. k3d queda sin
observabilidad en esta fase a propósito: montar el mismo stack en el cluster es trabajo de otra
iteración y `scripts/k3d-*.sh` no cambia.
