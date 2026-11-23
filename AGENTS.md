# AGENTS.md — EventFlow

Monorepo of Spring Boot services for a ticket-booking portfolio project. One Gradle project
per service, each owning its own database; `catalog`, `booking` and `notification` exist so far. The product
specification is in `docs/` (Spanish) and the workflow rules for agents in
`docs/07-opencode.md`. `EVENTFLOW_HANDOFF.md` is the index and lists the invariants.

## Commands

```bash
docker compose --profile core up -d   # PostgreSQL on :5432, RabbitMQ on :5672 (required by bootRun)
./gradlew build                       # compile + all tests (Docker required)
./gradlew :catalog:test               # tests only
./gradlew :catalog:test --tests "com.eventflow.catalog.event.EventDomainTest"
./gradlew :catalog:bootRun            # Catalog API on localhost:8081
./gradlew :booking:test               # Booking tests (domain, API, concurrency, expiry, events)
./gradlew :booking:bootRun            # Booking API on localhost:8082
./gradlew :notification:test          # Notification tests (consumer, mailbox API)
./gradlew :notification:bootRun       # Notification API on localhost:8083
```

- There is no system Gradle: always use `./gradlew` (wrapper 9.9.2).
- This machine has no `java` on `PATH`. Export `JAVA_HOME` to a JDK 21 before running Gradle;
  the local one is `~/.jdks/graalvm-ce-21.0.2`.
- The Gradle projects are `:catalog` → `services/catalog`, `:booking` → `services/booking` and
  `:notification` → `services/notification`.
  Do not "normalize" it to `:services:catalog`: that creates an intermediate `:services`
  project which inherits the Boot plugin and fails `bootJar` with no main class.

## Tests

- Integration tests start `postgres:18-alpine` and `rabbitmq:4-management-alpine` through
  Testcontainers, so **Docker is a hard prerequisite**; there is no skip switch, no H2 and no
  mocked broker anywhere (ADR 0003).
- A service's test base class starts its containers **once per JVM** and never stops them, and
  each test class declares its own `@SpringBootTest(properties = …)`; the Spring context is cached
  across classes with the same configuration, so a context must not outlive its containers.
- Each service owns its cleanup script: catalog truncates `outbox_event, ticket_type, event, venue`
  (`services/catalog/src/test/resources/db/cleanup.sql`), booking truncates `outbox_event,
  processed_message, reservation_item, reservation, event_inventory, idempotency_request`
  (`services/booking/src/test/resources/db/cleanup.sql`), notification truncates `notification,
  processed_message` (`services/notification/src/test/resources/db/cleanup.sql`). Add new tables
  there. Broker queues are not transactional: purge the queue and its DLQ before a test that
  asserts message counts.
- `spring.jpa.hibernate.ddl-auto=validate` runs on every context load: a test failing with a
  schema-validation error means the entity and the migrations disagree.
- Booking's concurrency test is the Phase 2 gate: 100 units, 200 simultaneous requests, exactly
  100 held, never negative, `available + held + sold = capacity`.
- The Phase 3 gate: `EventPublicationIntegrationTest` (a stopped broker leaves the event pending in
  the outbox and the relay publishes it after the broker returns), `EventPublishedConsumerTest`
  and `NotificationConsumerTest` (a duplicate delivery produces one effect, an unreadable event
  ends in the DLQ). The outage test pins the AMQP host port because Docker reassigns a random port
  when a container restarts.

## Layout and ownership

- Package-by-feature inside each service: `event/`, `venue/`, `shared/`, `config/`
  (ADR 0005). Each slice owns entity, value objects, repository, service, controller and
  `dto/`. Do not reintroduce `domain/`, `application/` or `api/` layers.
- Booking slices: `inventory/` (EventInventory, conditional hold/release, provisioning,
  availability, `EventPublished` consumer and its topology) and `reservation/` (Reservation
  aggregate, idempotent creation, cancel, expiry worker, event payload). `shared/` holds
  exceptions, the RFC 9457 handler, `CurrentUserProvider` and `shared/messaging/`.
- Notification slices: `notification/` (Notification entity, booking-events consumer, mailbox
  REST API) plus the same `shared/` primitives and `shared/messaging/`.
- `TicketType` belongs to `event/`: `Event.draft` enforces capacity, price and unique-name
  invariants across its ticket types. Splitting it out breaks the aggregate.
- Business rules live in aggregates (`Event.draft`, `Event.publish`, `Reservation.cancel`,
  `Reservation.expire`), never in controllers, services or listeners. `shared/` holds
  cross-slice primitives only — no business logic.
- Inventory counters are the documented exception: they move only through the conditional
  statements in `EventInventoryRepository` and a database `CHECK` enforces
  `available + held + sold = capacity` (ADR 0006). Never add a read-then-write hold.
- `EventService` may use `VenueRepository` to reference the venue aggregate; `ReservationService`
  may use `EventInventoryRepository` for the same reason. Services must not call another
  aggregate's service methods.
- Constructor injection only: `final` fields plus `@RequiredArgsConstructor`. No field
  `@Autowired`.
- `GET /api/events` returns only `PUBLISHED` events; publishing is `DRAFT -> PUBLISHED` and a
  second call is `409`. Errors are RFC 9457 with a stable `code` and `traceId`
  (`shared/web/ApiExceptionHandler`); route new failures through it.
- Mapping is explicit in `Response.from(entity)` factories. Never serialize entities or
  Spring's `Page` directly.
- The demo user is the `X-User-Id` header behind `CurrentUserProvider`; it is not authentication
  and must never be presented as such. Notification follows the documented contract and takes
  `userId` from the query string instead.
- **Messaging (ADR 0009):** a producer writes `outbox_event` **inside** the transaction of the
  business change through `shared/messaging/EventPublisher`; never publish from a controller,
  listener or a `@Transactional(propagation = REQUIRES_NEW)` helper. A listener claims the inbox
  (`Inbox.claim`) and performs its effect in one transaction, and the container acks after the
  commit. Permanent failures throw `AmqpRejectAndDontRequeueException`; transient ones are left to
  the retry queues, never caught and swallowed. Queue names, routing keys and consumer names are
  constants in the slice that owns the queue, and the consumer name must not change.

## Database

- Flyway is the only schema source. Add `V<n>__description.sql`; never edit an applied
  migration. One PostgreSQL instance, one database per service (`catalog_db`, `booking_db`,
  `notification_db`); never query another service's database.
  `docker/postgres/init/01-create-databases.sh` creates the extra databases on a fresh volume; an
  existing volume needs `docker compose down -v` or `createdb`.
- Every producer has `outbox_event`; every consumer has `processed_message` (plus `notification`,
  whose `event_id` is also unique). Those tables are owned by the service that migrates them.
- Inventory has exactly one trigger: the `EventPublished` consumer calling
  `InventoryService.provision` inside its inbox transaction (ADR 0009). There is no internal HTTP
  endpoint; a different offer for an already-provisioned event is a permanent failure that ends in
  the DLQ.

## Toolchain quirks (verified against Boot 4.2.0)

- Boot 4 modular starters: `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-flyway`,
  and `spring-boot-starter-rabbitmq` for RabbitMQ AMQP 0.9.1 (`spring.rabbitmq.*`).
  `spring-boot-starter-amqp` is the generic AMQP 1.0 client and is *not* what this project uses.
- Spring AMQP 4.2 exchanges raw `Message`s here: producers send a pre-serialized envelope and
  consumers parse it with the Boot `JsonMapper`, so no `__TypeId__` header leaves the service.
  Boot applies a unique `MessageRecoverer` bean to the listener factory; that is how the retry
  queues are wired.
- RabbitMQ 4 refuses non-exclusive transient queues (`transient_nonexcl_queues`): declare queues
  durable, and in tests use a durable probe queue plus a purge instead of a transient one.
- Docker assigns a new host port to a restarted container with a random binding. A test that
  stops and starts a broker must pin the host port (`setExposedPorts` + `setPortBindings`) and must
  not expect the broker to keep its queues: Testcontainers recreates the container.
- JSON databind is Jackson 3 (`tools.jackson.*`); annotations remain
  `com.fasterxml.jackson.annotation`.
- Versions come from the Boot BOM. `compileOnly` and `annotationProcessor` do **not** inherit
  `implementation(platform(...))`: add the platform to each configuration that needs it.
- springdoc 3.2.1 targets the Boot 4.2 line; upgrading Boot requires a matching springdoc.
- PostgreSQL 18 images expect the volume at `/var/lib/postgresql`, not `.../data`.
- Lombok: getters, JPA/injection constructors and loggers only. No `@Data`, `@Setter` or
  `@Builder` on entities and no Lombok on records (ADR 0004).
- Before adding a dependency or using a framework API, verify version/API against Context7 or
  official docs; never invent versions (`docs/07-opencode.md`, rules 4-5).

## Conventions

- Conventional Commits, no AI attribution or co-author trailers.
- `main` holds the bootstrap; feature work goes on `feat/<name>`. Push, PR and merge are human
  decisions.
- Phase tracking lives in `odd/tasks/*.md` (specs `S#`, tasks, log); architecture decisions in
  `docs/adr/`. Update both when behavior changes.
