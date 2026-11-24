# EventFlow — handoff para OpenCode

**Objetivo:** portfolio fullstack que demuestre diseño de sistemas distribuidos mediante reservas de entradas, sala de espera y pago simulado. **Duración:** 8 semanas, unas 120 h. **Entorno:** local, Docker, 32 GB RAM, sin servicios de pago.

## Cómo usar esta especificación

Lee primero este índice y `docs/07-opencode.md`. Consulta los demás documentos según la fase: no cargues todo el contexto en cada tarea.

| Documento | Cuándo consultarlo |
|---|---|
| `docs/01-arquitectura.md` | Contexto, servicios y stack. |
| `docs/02-dominio-datos.md` | Reservas, inventario, estados y persistencia. |
| `docs/03-apis-eventos.md` | Contratos REST, eventos RabbitMQ y errores. |
| `docs/04-frontend-redis.md` | UI, sala de espera y Redis. |
| `docs/05-infra-observabilidad.md` | Docker, Kubernetes y telemetría. |
| `docs/06-roadmap-calidad.md` | Orden de entrega, pruebas y criterios de aceptación. |
| `docs/07-opencode.md` | Reglas del agente y prompt inicial. |

## Invariantes no negociables

1. PostgreSQL es la fuente de verdad de reservas e inventario. Redis nunca decide si una entrada está vendida o disponible.
2. Cada servicio es propietario de su base de datos. No compartir entidades JPA, repositorios ni tablas.
3. Evitar sobreventa con operaciones SQL atómicas/transacciones; no confiar en un `GET` seguido de un `UPDATE` sin protección.
4. Publicar eventos mediante Transactional Outbox. Asumir entrega al menos una vez y consumidores idempotentes; no prometer exactly-once.
5. Pago y reserva son una Saga: no hay transacción ACID global. Definir compensación para pagos tardíos.
6. Cada fase debe compilar, pasar sus tests y dejar una entrega ejecutable antes de avanzar.
7. No inventar versiones ni APIs: consultar Context7 y documentación oficial; registrar incompatibilidades y decisiones.

## Alcance MVP

Cuatro servicios: Catalog, Booking, Payment y Notification. React SPA. PostgreSQL, RabbitMQ y Redis. Pago simulado; Docker Compose; después k3d, Prometheus, Grafana y trazas OpenTelemetry/Tempo. Fuera: Kafka, Event Sourcing, Keycloak, pagos reales, Service Mesh y despliegue cloud.

## Primer paso

OpenCode debe inspeccionar el repositorio, leer `docs/07-opencode.md` y proponer la Fase 0. Implementar solo bootstrap y Catalog en la primera iteración. No generar todos los servicios de una vez.

## Material de portfolio (Fases 7 y 8)

Para entender qué demuestra el proyecto sin leer todo el código, en este orden:

1. `README.md` — el problema, la arquitectura y una tabla que enlaza cada afirmación con su test.
2. `docs/adr/README.md` — las catorce decisiones, una línea cada una.
3. `docs/performance/README.md` — los escenarios k6 y los resultados medidos, con lo que **no** prueban.
4. `odd/tasks/` — una fase por documento: especificaciones, tareas, evidencia y decisiones tomadas.
5. `EVENTFLOW_HANDOFF.md` (este archivo) — invariantes no negociables y reglas del agente.
