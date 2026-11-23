# AGENTS.md — EventFlow

Monorepo of Spring Boot services for a ticket-booking portfolio project. One Gradle project
per service, each owning its own database; `catalog`, `booking`, `notification` and `payment` exist so far,
plus the `payment-simulator` provider double. The product
specification is in `docs/` (Spanish) and the workflow rules for agents in
`docs/07-opencode.md`. `EVENTFLOW_HANDOFF.md` is the index and lists the invariants.

## Commands

```bash
docker compose --profile core up -d   # PostgreSQL :5432, RabbitMQ :5672, Redis :6379 (required by bootRun)
./gradlew build                       # compile + all tests (Docker required)
./gradlew :catalog:test               # tests only
./gradlew :catalog:test --tests "com.eventflow.catalog.event.EventDomainTest"
./gradlew :catalog:bootRun            # Catalog API on localhost:8081
./gradlew :booking:test               # Booking tests (domain, API, concurrency, expiry, events, waiting room)
./gradlew :booking:bootRun            # Booking API on localhost:8082
./gradlew :notification:test          # Notification tests (consumer, mailbox API)
./gradlew :notification:bootRun       # Notification API on localhost:8083
./gradlew :payment:test               # Payment tests (consumer, saga gate, circuit breaker)
./gradlew :payment:bootRun            # Payment service on localhost:8084 (internal, no REST API)
./gradlew :payment-simulator:test     # Simulator amount rules
./gradlew :payment-simulator:bootRun  # Simulated provider on localhost:8085
cd frontend && npm ci && npm run dev  # React SPA on localhost:5173
cd frontend && npm test               # Vitest + Testing Library
cd frontend && npm run build          # tsc -b + Vite production build
```

- There is no system Gradle: always use `./gradlew` (wrapper 9.9.2).
- `bootRun` and `npm run dev` are long-running foreground processes that never return: run each in
  its own terminal (or background it) and do not read Gradle's `80% EXECUTING` progress as a hang.
- This machine has no `java` on `PATH`. Export `JAVA_HOME` to a JDK 21 before running Gradle;
  the local one is `~/.jdks/graalvm-ce-21.0.2`.
- The frontend needs Node 22+ and npm 11. `~/.npmrc` enforces `min-release-age=3` (and
  `ignore-scripts=true`): never bypass it, and pin versions outside that guard (ADR 0012).
- The Gradle projects are `:catalog` → `services/catalog`, `:booking` → `services/booking`,
  `:notification` → `services/notification`, `:payment` → `services/payment` and
  `:payment-simulator` → `services/payment-simulator`.
  Do not "normalize" it to `:services:catalog`: that creates an intermediate `:services`
  project which inherits the Boot plugin and fails `bootJar` with no main class.

## Tests

- Integration tests start `postgres:18-alpine`, `rabbitmq:4-management-alpine` and
  `redis:8-alpine` through Testcontainers, so **Docker is a hard prerequisite**; there is no skip
  switch, no H2 and no mocked broker or Redis anywhere (ADR 0003). Booking's base class pins the
  Redis host port so the outage test can restart the container.
- A service's test base class starts its containers **once per JVM** and never stops them, and
  each test class declares its own `@SpringBootTest(properties = …)`; the Spring context is cached
  across classes with the same configuration, so a context must not outlive its containers.
- Each service owns its cleanup script: catalog truncates `outbox_event, ticket_type, event, venue`
  (`services/catalog/src/test/resources/db/cleanup.sql`), booking truncates `outbox_event,
  processed_message, reservation_item, reservation, event_inventory, idempotency_request`
  (`services/booking/src/test/resources/db/cleanup.sql`), notification truncates `notification,
  processed_message` (`services/notification/src/test/resources/db/cleanup.sql`), and payment
  deletes `payment_attempt, payment, outbox_event, processed_message`
  (`services/payment/src/test/resources/db/cleanup.sql`; deletes instead of truncate so a delivery
  still finishing cannot deadlock the next test). Add new tables there. Broker queues are not transactional: purge the queue and its DLQ before a test that
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
- The Phase 4 gate: `PaymentConsumerTest` (approval, rejection, duplicate, provider timeout to the
  DLQ, refund), `PaymentGatewayCircuitBreakerTest` (the breaker opens and fails fast),
  `PaymentSagaIntegrationTest` (checkout, confirm, reject, duplicate, late payment compensated) and
  `NotificationConsumerTest` (`BOOKING_PAYMENT_FAILED`). Payment tests call a real HTTP double
  (`FakePaymentProvider`), never a mocked gateway.
- The Phase 5 gate: `WaitingRoomIntegrationTest` (join, stable position, batch admission, token
  issue/expiry, `403 NOT_ADMITTED`, gate off without a queue), `WaitingRoomRateLimitTest` (429 +
  `Retry-After`), `RateLimitSharedStateTest` (two real Booking application instances share one
  Redis window) and `WaitingRoomRedisOutageTest` (stop/restart Redis: 503 for the room, sales still
  correct and never oversold). Frontend: `npm test` covers the critical flow and the
  waiting/reservation states; `npm run build` is a gate.

## Layout and ownership

- Package-by-feature inside each service: `event/`, `venue/`, `shared/`, `config/`
  (ADR 0005). Each slice owns entity, value objects, repository, service, controller and
  `dto/`. Do not reintroduce `domain/`, `application/` or `api/` layers.
- Booking slices: `inventory/` (EventInventory, conditional hold/release/confirm, provisioning,
  availability, `EventPublished` consumer and its topology), `reservation/` (Reservation
  aggregate, idempotent creation, checkout, cancel, expiry worker, payment-result consumer and
  refund compensation) and `waitingroom/` (queue, admission dispatcher, admission tokens; ADR 0011).
  `shared/` holds exceptions, the RFC 9457 handler, `CurrentUserProvider`,
  `shared/web/rate/` and `shared/messaging/`.
- `waitingroom/` is a throttle, never the stock authority: it admits batches only while PostgreSQL
  has `available > 0`, and the token is required only while the event's queue exists. While Redis
  is down join/status answer `503` but the gate and the limiter fail open, so a sale is never
  blocked or oversold by a Redis failure. Never make Redis decide availability, and never add a
  read-then-write hold behind the gate.
- `frontend/` is the React SPA (Vite + TypeScript + Tailwind). It calls its own origin under
  `/api/catalog`, `/api/booking`, `/api/notification`; the Vite dev/preview proxy maps them to the
  services. Keep the slices under `src/api`, `src/features`, `src/components`, `src/hooks` and
  `src/routes`; render backend states, never a simulated confirmation; pin exact versions in
  `package.json` and `package-lock.json` (ADR 0012).
- Payment slices: `payment/` (Payment aggregate, attempts, `PaymentRequested` consumer and
  `PaymentRefundRequested` compensation, outbox, topology), `provider/` (`PaymentGateway` and its
  HTTP implementation behind the circuit breaker) and `shared/`; `config/` holds the properties,
  the RestClient/circuit-breaker beans and the listener recoverer.
- `services/payment-simulator` is the provider double: one controller, the amount rules and its
  properties. It owns no database, publishes no events and must not grow business rules.
- Notification slices: `notification/` (Notification entity, booking-events consumer, mailbox
  REST API) plus the same `shared/` primitives and `shared/messaging/`.
- `TicketType` belongs to `event/`: `Event.draft` enforces capacity, price and unique-name
  invariants across its ticket types. Splitting it out breaks the aggregate.
- Business rules live in aggregates (`Event.draft`, `Event.publish`, `Reservation.cancel`,
  `Reservation.expire`, `Reservation.confirm`, `Payment.refund`), never in controllers, services or
  listeners. `shared/` holds cross-slice primitives only — no business logic.
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
- **Messaging (ADR 0009, ADR 0010):** a producer writes `outbox_event` **inside** the transaction of
  the business change through `shared/messaging/EventPublisher`; never publish from a controller,
  listener or a `@Transactional(propagation = REQUIRES_NEW)` helper. A listener claims the inbox
  (`Inbox.claim`) and performs its effect in one transaction, and the container acks after the
  commit. Permanent failures throw `AmqpRejectAndDontRequeueException`; transient ones are left to
  the retry queues, never caught and swallowed. The recoverer writes the attempt number into
  `x-eventflow-retry-attempt` (never derive it from `x-death`: the broker replaces that header) and
  rethrows permanent failures so they skip the retry budget. Queue names, routing keys and consumer
  names are constants in the slice that owns the queue, and the consumer name must not change.
- **Payment saga (ADR 0010):** `POST /checkout` writes `PaymentRequested` with the reservation
  transition; the provider call, the `payment_attempt` row, the payment transition and the result
  event share the consumer transaction. A late authorization is never confirmed: it expires and
  releases when needed and publishes `PaymentRefundRequested`. Only conditional inventory
  statements may move `held -> sold` or `held -> available`.

## Database

- Flyway is the only schema source. Add `V<n>__description.sql`; never edit an applied
  migration. One PostgreSQL instance, one database per service (`catalog_db`, `booking_db`,
  `notification_db`, `payment_db`); never query another service's database.
  `docker/postgres/init/01-create-databases.sh` creates the extra databases on a fresh volume; it is
  idempotent, so an existing volume recovers with
  `docker compose exec -T postgres sh /docker-entrypoint-initdb.d/01-create-databases.sh`
  (no `down -v` needed).
- Every producer has `outbox_event`; every consumer has `processed_message` (plus `notification`,
  whose `event_id` is also unique). Those tables are owned by the service that migrates them.
- Payment owns `payment` (one row per reservation, unique `reservation_id`) and `payment_attempt`
  (one row per provider answer). A rolled-back attempt leaves no row by design; never insert it
  from a separate transaction to "keep" a timeout.
- Inventory has exactly one trigger: the `EventPublished` consumer calling
  `InventoryService.provision` inside its inbox transaction (ADR 0009). There is no internal HTTP
  endpoint; a different offer for an already-provisioned event is a permanent failure that ends in
  the DLQ. The reservation status `CHECK` accepts
  `HELD, PENDING_PAYMENT, CONFIRMED, PAYMENT_FAILED, EXPIRED, CANCELLED`; keep it in sync with
  `ReservationStatus`.

## Toolchain quirks (verified against Boot 4.2.0)

- Boot 4 modular starters: `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-flyway`,
  `spring-boot-starter-rabbitmq` for RabbitMQ AMQP 0.9.1 (`spring.rabbitmq.*`),
  `spring-boot-starter-data-redis` for the waiting room (`spring.data.redis.*`, Lettuce), and
  `spring-boot-starter-restclient` for `RestClient` plus `spring.http.clients.*` timeouts.
  `spring-boot-starter-amqp` is the generic AMQP 1.0 client and is *not* what this project uses.
- Redis scripts return one integer each (`DefaultRedisScript<Long>`); do not rely on multi-bulk
  result serialization with `StringRedisTemplate`. Rate-limit counters and the waiting-room queue
  live only in Redis, and the `waiting-events` registry replaces `KEYS`/`SCAN` in the dispatcher.
- Spring AMQP 4.2 exchanges raw `Message`s here: producers send a pre-serialized envelope and
  consumers parse it with the Boot `JsonMapper`, so no `__TypeId__` header leaves the service.
  Boot applies a unique `MessageRecoverer` bean to the listener factory **only when listener retry
  is enabled**; `spring.rabbitmq.listener.simple.retry` is `enabled: true`, `max-retries: 0`,
  `stateless: true`, so one delivery is one handler attempt and the recoverer owns the backoff.
  A second consumer queue needs its own `SimpleRabbitListenerContainerFactory` bean whose advice
  chain is replaced after `configurer.configure(...)`; a second `MessageRecoverer` bean would leave
  both factories without one.
- Resilience4j is used through its core `resilience4j-circuitbreaker` artifact (2.4.0), configured
  in code; the Spring Boot starter targets the Boot 3 auto-configuration model.
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
