# Architecture decision records

Every record states the context, the decision and the consequences, and the ones that were revised
say so explicitly. ADRs are not a history of what was convenient: a rejected alternative appears
because it was on the table.

| ADR | Decision | Phase |
| --- | --- | --- |
| [0001](0001-version-stack.md) | Version stack and build baseline: JDK 21, Spring Boot 4.2, Gradle wrapper, pinned versions verified before use | 0 |
| [0002](0002-catalog-publication-model.md) | Catalog publication model and the capacity invariant of an event | 1 |
| [0003](0003-test-strategy.md) | Test strategy: real PostgreSQL, RabbitMQ and Redis through Testcontainers, no in-memory substitutes | 0 |
| [0004](0004-lombok-usage.md) | Lombok usage policy: getters, constructors and loggers only | 0 |
| [0005](0005-package-by-feature.md) | Package-by-feature layout inside each service | 1 |
| [0006](0006-booking-inventory-concurrency.md) | Inventory concurrency: conditional `UPDATE` in PostgreSQL, `available + held + sold = capacity` | 2 |
| [0007](0007-provisional-inventory-provisioning.md) | Provisional inventory provisioning before `EventPublished` (superseded by 0009) | 2 |
| [0008](0008-rabbitmq-over-kafka.md) | RabbitMQ over Kafka for the asynchronous integration | 3 (recorded in 8) |
| [0009](0009-messaging-outbox-inbox-dlq.md) | Transactional Outbox, Inbox and per-queue retry/DLQ; at-least-once, idempotent consumers | 3 |
| [0010](0010-payment-saga.md) | Payment saga, late-payment compensation and the provider circuit breaker | 4 |
| [0011](0011-redis-waiting-room.md) | Redis waiting room, admission tokens and distributed rate limiting — a throttle, never the stock authority | 5 |
| [0012](0012-frontend-spa.md) | React SPA with Vite, one origin through a proxy, exact frontend pins | 5 |
| [0013](0013-containerization-and-deployment.md) | Container images, the Compose `app` profile and k3d deployment | 6 |
| [0014](0014-observability.md) | Metrics, traces, logs and dashboards as code | 7 |

Phase 8 adds no new architectural record beyond 0008: continuous integration, the frontend lint
gates and the k6 scenarios execute existing decisions instead of introducing new ones.
