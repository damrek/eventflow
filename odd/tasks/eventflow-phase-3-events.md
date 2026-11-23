# EventFlow — Phase 3 (Events)

- **Objective:** asynchronous integration through RabbitMQ with a Transactional Outbox in every
  producer, idempotent consumers with an Inbox, a Notification service and a per-queue DLQ.
  Gate from `docs/06`: a broker outage loses no event, and a duplicate delivery produces no
  duplicate effect.
- **Branch:** `feat/events` (from `main` after the Booking merge).
- **Delivery strategy:** `ask-on-risk` (forecast ~2,000 authored lines → work-unit commits).
- **Test runner:** Gradle (`./gradlew :catalog:test :booking:test :notification:test`),
  JUnit 6, Testcontainers PostgreSQL and RabbitMQ.

## Specs

- **S1 — Notification module.** Gradle project `:notification` mapped to
  `services/notification`, own `notification_db`, Flyway as the only schema source,
  `spring.jpa.hibernate.ddl-auto=validate`, HTTP port `8083`.
  Acceptance: `./gradlew :notification:test` boots the context against real PostgreSQL and
  RabbitMQ containers.
- **S2 — Broker and database in Compose.** `compose.yaml` adds `rabbitmq:4-management` under the
  `core` profile (AMQP `5672`, management `15672`, durable queue support, healthcheck) and
  `docker/postgres/init/01-create-databases.sh` creates `notification_db`. `.env.example`
  documents every new variable.
- **S3 — Event envelope and Transactional Outbox.** Every producer writes an `outbox_event` row
  **in the same transaction** as its business change, storing the full versioned envelope of
  `docs/03-apis-eventos.md` (`eventId`, `eventType`, `eventVersion`, `occurredAt`,
  `aggregateId`, `correlationId`, `payload`) and its routing key. A scheduled relay publishes
  unpublished rows to the durable topic exchange `eventflow.events`, waits for the publisher
  confirm and only then sets `published_at`; a broker outage leaves rows unpublished and a later
  relay publishes them. Delivery is at-least-once, never exactly-once.
- **S4 — `EventPublished` from Catalog.** Publishing an event (`DRAFT -> PUBLISHED`) writes one
  `EventPublished` envelope with routing key `event.published` in the same transaction; a second
  publish keeps answering `409 EVENT_ALREADY_PUBLISHED` and writes no second event. Payload:
  `{eventId, name, startsAt, ticketTypes:[{ticketTypeId,name,price,capacity}]}`.
- **S5 — Booking consumes `EventPublished`.** Queue `booking.event-published` bound to
  `event.published`; the listener records `(consumer_name, event_id)` in `processed_message` and
  calls the existing `InventoryService.provision` **in one transaction**, so a duplicate delivery
  is a no-op and a rollback repeats both. The provisional
  `POST /api/internal/inventory/events/{eventId}` endpoint and its DTOs are deleted
  (ADR 0007 superseded); provisioning is reachable only through the event.
- **S6 — Booking emits reservation events.** `BookingCancelled` (routing key
  `booking.cancelled`, on cancel) and `BookingExpired` (`booking.expired`, on expiry) are written
  to the outbox in the same transaction that changes the reservation state and releases the
  units. Payload: `{reservationId, eventId, userId, items:[{ticketTypeId,quantity}]}`.
- **S7 — Notification service.** Queue `notification.booking-events` bound to
  `booking.confirmed`, `booking.cancelled` and `booking.expired`; each new event becomes one
  `notification` row (`user_id`, `type`, `content`, `status`, `created_at`) in the same
  transaction as its `processed_message` row. `GET /api/notifications?userId=&page=` returns
  `{content:[{id,userId,type,content,status,createdAt}],page,size,totalElements,totalPages}`
  newest first; a missing or blank `userId` → `400 USER_ID_REQUIRED`; `page` starts at `0`,
  `size` is `1..100` (contract `docs/03-apis-eventos.md`).
- **S8 — Retries and DLQ.** Every consumer queue dead-letters into `<queue>.dlq` through the
  durable exchange `eventflow.events.dlx`. A failing delivery is republished to
  `<queue>.retry.N` (message TTL from configuration, default `5s`, `15s`, `45s`), which returns it
  to the business queue through `eventflow.events`; after the last attempt the message is
  rejected without requeue and lands in the DLQ. Consumer ACK happens only after the consumer
  transaction commits; a malformed envelope or unknown `eventVersion` is rejected immediately
  instead of retried.
- **S9 — Gate tests.** (a) With the broker stopped, publishing an event in Catalog leaves the
  outbox row unpublished; after the broker returns, the relay publishes it and a probe queue
  receives the envelope. (b) The same `EventPublished` delivered twice provisions inventory once
  and leaves one inbox row; the same `BookingCancelled` delivered twice writes one notification.
  (c) A permanently failing delivery ends in the DLQ after the configured retries. No H2, no
  mocks for broker or database.
- **S10 — Documentation.** README (RabbitMQ in Compose, event flow, Notification API),
  `AGENTS.md`, `.env.example`, ADR 0009 (outbox, at-least-once and retry/DLQ policy), ADR 0007
  marked superseded.

Out of scope: `PaymentRequested`/`PaymentAuthorized`/`PaymentRejected` and `BookingConfirmed`
(the confirmation transition arrives with the Payment service in Phase 4), Redis and the waiting
room (Phase 5), metrics/tracing (Phase 7).

## Tasks

| ID | Task | Specs | Route | Status |
| -- | ---- | ----- | ----- | ------ |
| T1 | Notification module, `notification_db`, RabbitMQ in Compose, env | S1, S2 | inline | done |
| T2 | Catalog: envelope, outbox, relay with publisher confirms, `EventPublished` | S3, S4 | inline | done |
| T3 | Booking: envelope, outbox, relay, Rabbit topology | S3 | inline | done |
| T4 | Booking: `EventPublished` consumer with inbox; delete the internal endpoint | S5 | inline | done |
| T5 | Booking: `BookingCancelled` and `BookingExpired` outbox writes | S6 | inline | done |
| T6 | Notification: consumer, inbox, notification slice and REST API | S7 | inline | done |
| T7 | Retry queues and DLQ for both consumers | S8 | inline | done |
| T8 | Gate tests: broker outage, duplicate delivery, DLQ | S9 | inline | done |
| T9 | README, AGENTS, `.env.example`, ADR 0009, supersede 0007 | S10 | inline | done |

## Log

- **L1 (user request, verbatim):** `let's to implement the next phase`
- **L2 (phase identified):** `docs/06-roadmap-calidad.md` orders Phase 3 as Events: RabbitMQ,
  Outbox/Inbox, Notification and DLQ, gate "broker outage loses no event; duplicates do not
  duplicate effects". Phases 0-2 are merged on `main`; `EVENTFLOW_HANDOFF.md` forbids skipping to
  later phases.
- **L3 (verified before choosing dependencies):** `spring-boot-starter-rabbitmq` is the Boot 4.2
  starter for RabbitMQ AMQP 0.9.1 (`spring.rabbitmq.*`); `spring-boot-starter-amqp` is the generic
  AMQP 1.0 client. Spring AMQP 4.2.0 ships `JacksonJsonMessageConverter` (Jackson 3) and
  `org.testcontainers:testcontainers-rabbitmq` 2.0.5 ships
  `org.testcontainers.rabbitmq.RabbitMQContainer`, supported by `@ServiceConnection` through
  `spring-boot-rabbitmq`'s connection-details factory.
- **L4 (decisions):** producers and consumers exchange raw JSON `Message`s and parse the envelope
  with the Boot `JsonMapper`, so no `__TypeId__` class headers travel over the wire. Each service
  owns its copy of the `shared/messaging` primitives (envelope, outbox, relay, inbox), matching
  the existing per-service duplication of `shared/domain` and `shared/web` and keeping the
  databases independent. Retries are queue-based (`retry.N` with message TTL) rather than
  in-process, so backoff survives a restart and never blocks a consumer thread.

- **L5 (implementation):** `services/notification` added with its mailbox slice and booking-events
  consumer; Catalog and Booking gained `shared/messaging` (envelope, outbox, relay, inbox, retry
  topology, recoverer) plus a slice-owned queue definition and listener; the provisional
  `POST /api/internal/inventory/events/{id}` endpoint and its DTOs were deleted (ADR 0007
  superseded). Composer gained `rabbitmq:4-management-alpine` and the init script creates
  `notification_db`.
- **L6 (evidence, `./gradlew test --rerun-tasks`):** BUILD SUCCESSFUL, 81 tests — catalog 36,
  booking 40, notification 5. Gate tests: `EventPublicationIntegrationTest` stops the broker,
  publishes (the outbox row stays unpublished across relay ticks) and sees the row published
  after the broker returns; `EventPublishedConsumerTest` provisions inventory from one event,
  ignores the same envelope delivered twice, and dead-letters an unsupported `eventVersion` and a
  conflicting offer; `NotificationConsumerTest` records one mailbox entry per event, ignores a
  duplicate, paginates newest first and dead-letters an event it does not consume.
- **L7 (problems found while proving the gate):** (a) RabbitMQ 4 refuses non-exclusive transient
  queues, so the test probe queue is durable and purged; (b) Docker assigns a new host port when a
  randomly-bound container restarts, so the outage test pins the AMQP host port
  (`setExposedPorts` + `setPortBindings`) and asserts the outbox row instead of the message,
  because Testcontainers recreates (and empties) the broker on restart; (c) the mailbox test
  needed to await the consumer instead of assuming delivery before the HTTP call.
- **L8 (delivery):** work-unit commits on `feat/events`; push, PR and merge stay a human decision.
