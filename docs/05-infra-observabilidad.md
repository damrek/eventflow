# 05 — Infraestructura y observabilidad

## Desarrollo local

Docker Compose levanta PostgreSQL, RabbitMQ, Redis, simulador de pagos y los servicios implementados. Perfiles separados: `core` (dependencias), `app` y `observability`. Healthchecks y volúmenes persistentes para servicios stateful. Proporcionar `.env.example`; no guardar secretos reales.

Cada servicio debe tener Dockerfile multi-stage, ejecutar como usuario no root y exponer Actuator health. Configuración por variables de entorno. El frontend se sirve detrás de un reverse proxy sencillo.

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
