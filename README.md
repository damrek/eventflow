# EventFlow

Portfolio fullstack project that demonstrates distributed-systems design through ticket
reservations, a waiting room and a simulated payment provider. The goal is not feature
count: it is a small system where every concurrency, idempotency and failure-handling
claim is provable with tests.

**Current status: Phase 0 (bootstrap), Phase 1 (Catalog) and Phase 2 (Booking) complete.**

## Stack

Versions are verified against Context7, Maven Central metadata and official documentation
before being added (see `docs/adr/0001-version-stack.md`).

| Layer        | Technology                                          |
| ------------ | --------------------------------------------------- |
| Backend      | Java 21, Spring Boot 4.2.0, Spring MVC, Spring Data JPA |
| Build        | Gradle 9.9.2 (wrapper), version catalog             |
| Boilerplate  | Lombok 1.18.48 (build-time only, policy in ADR 0004) |
| Database     | PostgreSQL 18, Flyway 12.11.0 (managed by Boot)     |
| API docs     | springdoc-openapi 3.2.1 (Swagger UI)                |
| Tests        | JUnit 6, AssertJ, Testcontainers 2.0.5              |
| Local infra  | Docker Compose (PostgreSQL, profile `core`)         |

## Prerequisites

- JDK 21 (`java -version` must report 21). If `java` is not on your `PATH`, export
  `JAVA_HOME` pointing to a JDK 21 installation; the Gradle wrapper reads it.
- Docker with Compose v2

Gradle is not required: use the wrapper.

## Quick start

```bash
cp .env.example .env
docker compose --profile core up -d          # PostgreSQL on localhost:5432
./gradlew :catalog:bootRun          # Catalog API on localhost:8081
./gradlew :booking:bootRun          # Booking API on localhost:8082
```

One PostgreSQL instance hosts one database per service (`catalog_db`, `booking_db`). The
`booking_db` database is created by `docker/postgres/init/01-create-databases.sh` when the volume
is first initialised; if you already had a volume from Phase 1, run `docker compose down -v` once
(or `createdb -U eventflow booking_db`) before starting Booking.

- Swagger UI: http://localhost:8081/swagger-ui.html and http://localhost:8082/swagger-ui.html
- OpenAPI JSON: http://localhost:8081/v3/api-docs and http://localhost:8082/v3/api-docs
- Health: http://localhost:8081/actuator/health and http://localhost:8082/actuator/health

## Tests

```bash
./gradlew build              # compiles, runs unit + integration tests of every service
./gradlew :catalog:test      # one service
./gradlew :booking:test
```

Integration tests start a real PostgreSQL container through Testcontainers. No H2 and
no in-memory database is used anywhere (see `docs/adr/0003-test-strategy.md`).

## API (Catalog)

| Method | Path                              | Description                                  |
| ------ | --------------------------------- | -------------------------------------------- |
| POST   | `/api/venues`                     | Create a venue                               |
| GET    | `/api/venues`                     | List venues                                  |
| POST   | `/api/events`                     | Create an event in `DRAFT` with ticket types  |
| GET    | `/api/events?from=&to=&page=&size=` | Paginated list of `PUBLISHED` events       |
| GET    | `/api/events/{eventId}`           | Event detail with ticket types               |
| POST   | `/api/events/{eventId}/publish`   | Publish a `DRAFT` event                      |

Errors use `application/problem+json` (RFC 9457) with `code` and `traceId` extensions.
`X-Request-Id` is echoed back and used as `traceId` when present.

### Example

```bash
curl -s -X POST localhost:8081/api/venues -H 'Content-Type: application/json' -d '{
  "name": "Arena Sur", "address": "Calle 1, Madrid", "capacity": 5000
}'

curl -s -X POST localhost:8081/api/events -H 'Content-Type: application/json' -d '{
  "name": "Java Meetup 2026",
  "venueId": "<venue-id>",
  "startsAt": "2026-12-01T19:00:00Z",
  "ticketTypes": [
    { "name": "General", "price": 25.00, "capacity": 400 },
    { "name": "VIP", "price": 60.00, "capacity": 50 }
  ]
}'

curl -s -X POST localhost:8081/api/events/<event-id>/publish
```

## API (Booking)

| Method | Path                                        | Description                                         |
| ------ | ------------------------------------------- | --------------------------------------------------- |
| GET    | `/api/events/{eventId}/availability`        | Capacity/available/held/sold per ticket type        |
| POST   | `/api/reservations`                         | Hold units; requires `X-User-Id` + `Idempotency-Key` |
| GET    | `/api/reservations/{reservationId}`         | Read the caller's reservation                       |
| POST   | `/api/reservations/{reservationId}/cancel`  | Cancel a `HELD` reservation and release its units   |
| POST   | `/api/internal/inventory/events/{eventId}`  | Provisional inventory provisioning (ADR 0007)       |

Inventory is authoritative in PostgreSQL: units move `available → held` with one conditional
`UPDATE`, and a database `CHECK` keeps `available + held + sold = capacity` (ADR 0006). The
expiry worker returns units from overdue holds; `Idempotency-Key` makes a retried creation
return the original response instead of holding twice.

```bash
EVENT=<event-id>
TYPE=<ticket-type-id>

# Provisional until Phase 3 wires EventPublished (ADR 0007)
curl -s -X POST localhost:8082/api/internal/inventory/events/$EVENT \
  -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"ticketTypeId\":\"$TYPE\",\"price\":25.00,\"capacity\":400}]}"

curl -s -X POST localhost:8082/api/reservations \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: demo-user' -H 'Idempotency-Key: order-1' \
  -d "{\"eventId\":\"$EVENT\",\"items\":[{\"ticketTypeId\":\"$TYPE\",\"quantity\":2}]}"

curl -s localhost:8082/api/events/$EVENT/availability
```

## Repository layout

```
services/catalog/     Catalog service (venues, events, ticket types, publication)
  src/main/java/com/eventflow/catalog/
    event/            Event aggregate: Event, TicketType, repository, service, controller, dto/
    venue/            Venue aggregate: Venue, repository, service, controller, dto/
    shared/           Cross-slice primitives: exceptions, RFC 9457 handler, page envelope
    config/           Clock and OpenAPI beans
services/booking/     Booking service (inventory, reservations, expiration)
  src/main/java/com/eventflow/booking/
    inventory/        EventInventory, conditional hold/release, provisioning, availability API
    reservation/      Reservation aggregate, idempotent creation, cancel, expiry worker
    shared/           Cross-slice primitives: exceptions, RFC 9457 handler, CurrentUserProvider
    config/           Clock, OpenAPI and BookingProperties
docs/                 Product specification, roadmap, ADRs, performance notes
odd/tasks/            Feature/task tracking documents
docker/postgres/init/ One database per service on a fresh volume
compose.yaml          Local infrastructure (profile: core)
```

## Conventions

- **Constructor injection, no field injection.** Dependencies are `final` fields, generated
  by Lombok's `@RequiredArgsConstructor`. A single constructor needs no `@Autowired`: Spring
  injects it implicitly. This keeps components testable without a Spring context, makes a
  missing bean fail at startup instead of at first use, and prevents mutable dependencies.
- **One package per aggregate** with its entity, repository, service, controller and DTOs
  (ADR 0005). `shared/` holds primitives only.
- **Lombok policy:** getters, JPA constructors, injection constructors and loggers only; no
  `@Data`/`@Setter`/`@Builder` on entities and no Lombok on records (ADR 0004).
- **Errors:** every failure returns `application/problem+json` with a stable `code` and the
  request `traceId`.

## Roadmap

| Phase | Scope                                    | Status      |
| ----- | ---------------------------------------- | ----------- |
| 0     | Bootstrap: monorepo, Compose, conventions | done        |
| 1     | Catalog: REST, JPA, Flyway, OpenAPI       | done        |
| 2     | Booking: inventory, reservations, expiry  | done        |
| 3     | Events: RabbitMQ, Outbox/Inbox            | pending     |
| 4     | Payment: saga, simulator, resilience       | pending     |
| 5     | Frontend: React SPA                        | pending     |
| 6     | Deployment: Compose and k3d                | pending     |
| 7     | Observability: Prometheus, Grafana, OTEL   | pending     |
| 8     | Portfolio: k6, CI, ADRs, demo              | pending     |

## Documentation

- `EVENTFLOW_HANDOFF.md` — project index and non-negotiable invariants
- `docs/01-arquitectura.md` … `docs/07-opencode.md` — product specification
- `docs/adr/` — architecture decision records
