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

## Dashboards

Grafana: (1) salud y JVM de servicios; (2) latencia/errores REST; (3) RabbitMQ, retries y DLQ; (4) reservas, expiraciones y sala de espera.

## Trazas y logs

Micrometer Tracing + OpenTelemetry OTLP → Collector → Tempo. Propagar contexto en HTTP y metadatos RabbitMQ. Logs JSON a stdout con `traceId`, `spanId`, `service`, `eventId` y `reservationId`; no incluir datos sensibles. Para el MVP consultar logs con Docker/kubectl; Loki queda como extensión.

## Carga con k6

Escenarios: navegación del catálogo, 500 usuarios compitiendo por 100 entradas, tasa de llegada a waiting room y degradación del simulador de pago. Medir throughput, p50/p95/p99, tasa de error, conflictos y consistencia del inventario. Guardar scripts y resultados; no inventar cifras ni prometer umbrales antes de medir.

## Restricción de recursos

Con 32 GB RAM, arrancar solo perfil core durante desarrollo. Activar observabilidad completa y k3d por separado; limitar memoria/CPU de contenedores y no ejecutar simultáneamente todas las réplicas y escenarios de carga.
