# EventFlow

Portfolio fullstack project that demonstrates distributed-systems design through ticket
reservations, a waiting room and a simulated payment provider. The goal is not feature
count: it is a small system where every concurrency, idempotency and failure-handling
claim is provable with tests.

**Current status: Phase 0 (bootstrap), Phase 1 (Catalog), Phase 2 (Booking), Phase 3 (Events) and
Phase 4 (Payment/Saga) complete.**

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
| Messaging    | RabbitMQ 4.2, Spring AMQP 4.2 (AMQP 0.9.1), Outbox/Inbox |
| Resilience   | Resilience4j 2.4.0 circuit breaker, Boot `RestClient` |
| Tests        | JUnit 6, AssertJ, Testcontainers 2.0.5, Awaitility   |
| Local infra  | Docker Compose (PostgreSQL + RabbitMQ, profile `core`) |

## Prerequisites

- JDK 21 (`java -version` must report 21). If `java` is not on your `PATH`, export
  `JAVA_HOME` pointing to a JDK 21 installation; the Gradle wrapper reads it.
- Docker with Compose v2

Gradle is not required: use the wrapper.

## Quick start

```bash
cp .env.example .env
docker compose --profile core up -d  # PostgreSQL on :5432, RabbitMQ on :5672 (UI :15672)
./gradlew :catalog:bootRun           # Catalog API on localhost:8081
./gradlew :booking:bootRun           # Booking API on localhost:8082
./gradlew :notification:bootRun      # Notification API on localhost:8083
./gradlew :payment-simulator:bootRun # Simulated payment provider on localhost:8085
./gradlew :payment:bootRun           # Payment service on localhost:8084 (internal, no REST API)
```

One PostgreSQL instance hosts one database per service (`catalog_db`, `booking_db`,
`notification_db`, `payment_db`). Those databases are created by
`docker/postgres/init/01-create-databases.sh` when the volume is first initialised; if you already
had a volume from an earlier phase, run `docker compose down -v` once (or `createdb` the missing
ones) before starting the services.

- Swagger UI: `http://localhost:8081/swagger-ui.html`, `:8082`, `:8083`
- OpenAPI JSON: `http://localhost:8081/v3/api-docs`, `:8082`, `:8083`
- Health: `http://localhost:8081/actuator/health`, `:8082`, `:8083`, `:8084`
- RabbitMQ management UI: http://localhost:15672 (`eventflow` / `eventflow`)

## Tests

```bash
./gradlew build                  # compiles, runs unit + integration tests of every service
./gradlew :catalog:test          # one service
./gradlew :booking:test
./gradlew :notification:test
./gradlew :payment:test
./gradlew :payment-simulator:test
```

Integration tests start real PostgreSQL and RabbitMQ containers through Testcontainers, and the
Payment tests talk to a real HTTP provider double. No H2, no in-memory database and no mocked
broker are used anywhere (see `docs/adr/0003-test-strategy.md`). The messaging tests prove the
Phase 3 gate (a stopped broker leaves the event in the outbox; duplicates produce a single effect)
and the Phase 4 gate (approved, rejected, timeout, duplicated event and late payment).

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
| POST   | `/api/reservations/{reservationId}/checkout` | `HELD -> PENDING_PAYMENT` and request the payment  |
| POST   | `/api/reservations/{reservationId}/cancel`  | Cancel a `HELD` reservation and release its units   |

Inventory is authoritative in PostgreSQL: units move `available → held` with one conditional
`UPDATE`, and a database `CHECK` keeps `available + held + sold = capacity` (ADR 0006). The
expiry worker returns units from overdue holds; `Idempotency-Key` makes a retried creation
return the original response instead of holding twice.

A reservation is `HELD` until the user pays. `checkout` requests the payment (`PaymentRequested`);
the payment result then moves it to `CONFIRMED` (units `held → sold`) or `PAYMENT_FAILED` (units
back to `available`). A reservation whose hold expires — `HELD` or `PENDING_PAYMENT` — becomes
`EXPIRED`, and an authorization that arrives afterwards is compensated with a refund instead of
selling released inventory (ADR 0010). `cancel` is only allowed while `HELD`.

Booking never learns an offering over HTTP: publishing the event in Catalog writes
`EventPublished` to the outbox, the relay publishes it, and Booking's consumer provisions the
inventory (ADR 0009). Give the broker a second to deliver before reserving.

```bash
# Publish in Catalog first; Booking provisions inventory from EventPublished.
curl -s -X POST localhost:8081/api/events/<event-id>/publish

EVENT=<event-id>
TYPE=<ticket-type-id>

curl -s -X POST localhost:8082/api/reservations \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: demo-user' -H 'Idempotency-Key: order-1' \
  -d "{\"eventId\":\"$EVENT\",\"items\":[{\"ticketTypeId\":\"$TYPE\",\"quantity\":2}]}"

curl -s localhost:8082/api/events/$EVENT/availability
```

## Payment (internal) and its simulator

Payment exposes no REST API: it consumes `payment.requested` and `payment.refund-requested` and
publishes `payment.authorized` or `payment.rejected`. Each reservation gets one logical payment, at
most one settled authorization and one audit row per answered provider call (`payment_attempt`);
the call runs inside the consumer transaction, so a timeout rolls the inbox, the payment and the
result event back together and the broker retries instead of finalizing a payment nobody answered.

The provider is the `:payment-simulator` app, a deterministic HTTP double. Its outcome depends on
the amount, so tests and demos need no shared switch:

| Amount                | Answer                                                       |
| --------------------- | ------------------------------------------------------------ |
| below `10.00`         | `REJECTED` (simulated decline)                               |
| cents `.98`           | HTTP `503` (provider outage; the breaker absorbs it)         |
| cents `.99`           | approves after `PAYMENT_SIMULATOR_TIMEOUT_DELAY` (client read timeout) |
| anything else         | `APPROVED`                                                   |

The gateway wraps every call in a Resilience4j circuit breaker (`PAYMENT_CB_*`): after the minimum
number of failed calls it opens, fails fast without calling the provider and lets the message wait
in its retry queue. HTTP timeouts come from `spring.http.clients.*`
(`PAYMENT_PROVIDER_CONNECT_TIMEOUT` / `PAYMENT_PROVIDER_READ_TIMEOUT`). Late authorizations are
compensated: Booking publishes `PaymentRefundRequested` and Payment marks the payment `REFUNDED`.

```bash
# Continue the booking example: request the payment, then watch the reservation and the broker.
RESERVATION=<reservation-id>
curl -s -X POST localhost:8082/api/reservations/$RESERVATION/checkout -H 'X-User-Id: demo-user'
curl -s localhost:8082/api/reservations/$RESERVATION -H 'X-User-Id: demo-user'
```

## API (Notification)

| Method | Path                                     | Description                          |
| ------ | ---------------------------------------- | ------------------------------------ |
| GET    | `/api/notifications?userId=&page=&size=` | A user's mailbox, newest first       |

Notification has no write endpoint: entries are created only by consuming `booking.confirmed`,
`booking.cancelled`, `booking.expired` and `booking.payment-failed`. `userId` is required
(`400 USER_ID_REQUIRED` when it is missing or blank), `page` starts at `0` and `size` accepts
`1..100`.

## Event flow

```
Catalog ──outbox──▶ eventflow.events ──▶ booking.event-published ──▶ Booking (inventory)
Booking ──outbox──▶ eventflow.events ──▶ payment.payment-requested ─▶ Payment (provider call)
Payment ──outbox──▶ eventflow.events ──▶ booking.payment-result ────▶ Booking (confirm/compensate)
Booking ──outbox──▶ eventflow.events ──▶ notification.booking-events ─▶ Notification (mailbox)
Booking ──outbox──▶ eventflow.events ──▶ payment.payment-requested ─▶ Payment (refund)
```

- **Producer side (ADR 0009):** every event is written to `outbox_event` in the same transaction as
  its business change. A relay publishes with publisher confirms and marks the row published only
  after the confirm, so a broker outage delays delivery instead of losing the event.
- **Consumer side:** the inbox row `(consumer_name, event_id)` commits with the business effect, so
  a redelivery is a no-op. Malformed envelopes and unknown `eventVersion`s are rejected at once.
- **Retries:** each consumer queue has three retry queues (TTL `5s`, `15s`, `45s`); after the last
  attempt the message lands in `<queue>.dlq`. The recoverer writes the attempt number into the
  message (`x-eventflow-retry-attempt`), so the history survives the republish, a broker restart
  and several replicas. Configure the delays with `EVENTFLOW_RETRY_DELAYS`.
- Delivery is at-least-once, never exactly-once; the mailbox is idempotent by construction.

## Repository layout

```
services/catalog/     Catalog service (venues, events, ticket types, publication)
  src/main/java/com/eventflow/catalog/
    event/            Event aggregate: Event, TicketType, repository, service, controller, dto/
    venue/            Venue aggregate: Venue, repository, service, controller, dto/
    shared/           Cross-slice primitives: exceptions, RFC 9457 handler, page envelope,
                      messaging (envelope, outbox and relay)
    config/           Clock, OpenAPI and the RabbitMQ exchange
services/booking/     Booking service (inventory, reservations, expiration, payment saga)
  src/main/java/com/eventflow/booking/
    inventory/        EventInventory, conditional hold/release/confirm, provisioning, availability
    reservation/      Reservation aggregate, idempotent creation, checkout, cancel, expiry worker,
                      payment-result consumer and refund compensation
    shared/           Cross-slice primitives: exceptions, RFC 9457 handler, CurrentUserProvider,
                      messaging (envelope, outbox, relay, inbox, retry topology, recoverer)
    config/           Clock, OpenAPI, BookingProperties and the listener container factories
services/payment/     Payment service (simulated provider authorization, circuit breaker, refund)
  src/main/java/com/eventflow/payment/
    payment/          Payment aggregate, attempts, consumer, outbox, topology
    provider/         PaymentGateway and its HTTP implementation behind the circuit breaker
    shared/           Cross-slice primitives and messaging (envelope, outbox, relay, inbox)
    config/           Clock, PaymentProperties, RestClient + circuit breaker, listener recoverer
services/payment-simulator/ Deterministic HTTP provider double (no database, no events)
services/notification/ Notification service (user mailbox)
  src/main/java/com/eventflow/notification/
    notification/     Notification entity, booking-events consumer, REST mailbox
    shared/           Cross-slice primitives and messaging (envelope, inbox, retry topology)
    config/           Clock, OpenAPI and the listener recoverer
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
| 3     | Events: RabbitMQ, Outbox/Inbox, Notification | done      |
| 4     | Payment: saga, simulator, resilience       | done        |
| 5     | Frontend: React SPA                        | pending     |
| 6     | Deployment: Compose and k3d                | pending     |
| 7     | Observability: Prometheus, Grafana, OTEL   | pending     |
| 8     | Portfolio: k6, CI, ADRs, demo              | pending     |

## Documentation

- `EVENTFLOW_HANDOFF.md` — project index and non-negotiable invariants
- `docs/01-arquitectura.md` … `docs/07-opencode.md` — product specification
- `docs/adr/` — architecture decision records
