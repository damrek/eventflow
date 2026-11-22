# 06 — Roadmap, pruebas y Definition of Done

Plan orientativo: 8 semanas × 15 h. Cada fase debe dejar un estado ejecutable. No avanzar si falla su puerta de calidad.

| Fase              | Entrega                                         | Puerta de calidad                                                      |
| ----------------- | ----------------------------------------------- | ---------------------------------------------------------------------- |
| 0. Bootstrap      | Monorepo, convenciones, Compose de PostgreSQL.  | Build reproducible y README de arranque.                               |
| 1. Catalog        | REST, JPA, Flyway, OpenAPI.                     | CRUD/publicación y tests con PostgreSQL real.                          |
| 2. Booking        | Inventario, reservas, expiración, idempotencia. | Test concurrente: 100 entradas, máximo 100 retenidas/vendidas.         |
| 3. Eventos        | RabbitMQ, Outbox/Inbox, Notification, DLQ.      | Caída del broker no pierde eventos; duplicados no duplican efectos.    |
| 4. Pago/Saga      | Payment + simulador HTTP + circuit breaker.     | Aprobado, rechazado, timeout, evento duplicado y pago tardío probados. |
| 5. Frontend       | Flujo completo SPA.                             | Usuario recorre catálogo → espera → reserva → pago → notificación.     |
| 6. Despliegue     | Docker Compose y k3d.                           | Arranque documentado; probes y dos réplicas de Booking.                |
| 7. Observabilidad | Prometheus, Grafana, OTEL y Tempo.              | Dashboard y traza visible de un flujo de reserva.                      |
| 8. Portfolio      | k6, CI, ADRs, README y demo.                    | Pipeline verde y resultados de carga reproducibles.                    |

## TDD y pruebas obligatorias

- Unitarias: reglas de estados, expiración, validaciones y política de reintentos.
- Integración con Testcontainers: PostgreSQL, RabbitMQ y Redis; no sustituirlos por mocks en pruebas de integración.
- Concurrencia: solicitudes simultáneas sobre inventario limitado; comprobar `available + held + sold = capacity` y que nunca hay negativos.
- Idempotencia HTTP: misma key/payload no crea otra reserva; misma key/payload distinto produce conflicto.
- Mensajería: Outbox pendiente se publica tras recuperar RabbitMQ; evento duplicado produce un único efecto; error permanente termina en DLQ.
- Saga: pago aprobado/rechazado/timeout, servicio temporalmente caído y autorización tardía.
- Redis: admisión, expiración de token, rate limit entre réplicas y reinicio de Redis.
- Frontend: tests de componentes/flujo crítico; build de producción.

## CI básico

En cada Pull Request: format/lint, backend unit tests, frontend lint/tests/build, integración Testcontainers y build de imágenes. Mantener jobs razonables; no ejecutar k6 pesado en cada PR. Fijar versiones/acciones y usar `npm ci`.

## Documentación del portfolio

README con problema, funcionalidades, arquitectura, tecnologías, comandos de arranque, diagramas y capturas/GIF. `docs/adr/` para decisiones: Vite vs Next, RabbitMQ vs Kafka, Outbox, inventario en PostgreSQL, Saga, Redis waiting room. `docs/performance/` para scripts, hardware, configuración y resultados reales de k6.

## Definition of Done global

- Clonar y arrancar siguiendo README sin pasos ocultos.
- Migraciones reproducibles y configuración de ejemplo.
- APIs documentadas y errores consistentes.
- Tests relevantes verdes; sin afirmar cobertura no medida.
- No secretos ni datos sensibles en Git/logs.
- Diagramas/ADRs actualizados.
- Evidencia de que no hay sobreventa, duplicación de efectos ni pérdida de Outbox en los escenarios probados.
