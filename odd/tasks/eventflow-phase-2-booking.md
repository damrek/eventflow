# EventFlow — Phase 2 (Booking)

- **Objective:** Booking service owning inventory and reservations: atomic holds, expiration,
  HTTP idempotency. Gate from `docs/06`: 100 tickets, never more than 100 held/sold.
- **Branch:** `feat/booking` (from `main` after the Catalog merge).
- **Delivery strategy:** `ask-on-risk` (forecast ~1,300 authored lines → several work units;
  chain strategy only on publish).
- **Test runner:** Gradle (`./gradlew :booking:test`), JUnit 6, Testcontainers PostgreSQL.

## Specs

- **S1 — Module and database.** Gradle project `:booking` mapped to `services/booking`, own
  `booking_db` (same PostgreSQL instance, never another service's tables), Flyway `V1` as the
  only schema source, `spring.jpa.hibernate.ddl-auto=validate`, HTTP port `8082`.
  Acceptance: `./gradlew :booking:test` boots the context against real PostgreSQL.
- **S2 — Inventory provisioning (provisional until Phase 3).** `POST
  /api/internal/inventory/events/{eventId}` with `{items:[{ticketTypeId,price,capacity}]}` →
  `201` and the availability body; identical replay → `200`; a different offer for an
  already-provisioned event → `409 INVENTORY_ALREADY_PROVISIONED`. Phase 3 replaces the trigger
  with the `EventPublished` consumer that calls the same use case.
- **S3 — Availability.** `GET /api/events/{eventId}/availability` →
  `{eventId, ticketTypes:[{ticketTypeId, price, capacity, available, held, sold}]}`. Unknown
  event → `404 INVENTORY_NOT_FOUND`. Public, no `X-User-Id`.
- **S4 — Create reservation.** `POST /api/reservations` requires `X-User-Id` and
  `Idempotency-Key`; body `{eventId, items:[{ticketTypeId, quantity}]}` → `201` with
  `{reservationId, eventId, userId, status: "HELD", expiresAt, createdAt,
  items:[{ticketTypeId, quantity, unitPrice}], totalAmount}`. Units move
  `available → held` with one conditional `UPDATE ... WHERE available >= :quantity` inside the
  transaction; no stock → `422 INSUFFICIENT_TICKETS`.
- **S5 — Idempotency.** Same user + key + payload returns the original status and body without
  a second hold; same key with a different payload → `409 IDEMPOTENCY_KEY_REUSED`; missing,
  blank or longer than 200 characters → `400 IDEMPOTENCY_KEY_REQUIRED`.
- **S6 — Read and cancel.** `GET /api/reservations/{reservationId}` returns the owner's
  reservation, otherwise `404 RESERVATION_NOT_FOUND` (no ownership leak).
  `POST /api/reservations/{reservationId}/cancel` moves `HELD → CANCELLED` and returns units to
  `available`; a cancelled or expired reservation → `409 RESERVATION_NOT_CANCELLABLE`, one past
  `expiresAt` → `409 RESERVATION_EXPIRED`. Missing `X-User-Id` → `400 USER_ID_REQUIRED`.
- **S7 — Expiration.** A scheduled worker claims due `HELD` reservations with
  `FOR UPDATE SKIP LOCKED`, transitions them to `EXPIRED` and releases their units in the same
  transaction, so several replicas cannot release twice. TTL, interval and batch size are
  configuration (`booking.reservation.ttl`, `booking.expiry.interval`,
  `booking.expiry.batch-size`).
- **S8 — Inventory invariant.** `available + held + sold = capacity`, all non-negative
  (database `CHECK`). Gate: capacity 100 and 200 concurrent requests keep exactly 100
  reservations, `held = 100`, `sold = 0`, `available = 0`, never a negative value.
- **S9 — Errors.** RFC 9457 `application/problem+json` with `type`, `title`, `status`,
  `detail`, `instance`, `code`, `traceId`; `X-Request-Id` echoed and reused as `traceId`, same
  conventions as Catalog.
- **S10 — Tests.** Domain unit tests plus integration tests against a real PostgreSQL container
  (Testcontainers 2.0.5). No H2. Concurrency, idempotency and expiry tests are mandatory.
- **S11 — OpenAPI.** `/v3/api-docs` and `/swagger-ui.html` served by springdoc 3.2.1.
- **S12 — Documentation.** README (Booking endpoints and demo flow), AGENTS.md, `.env.example`,
  `compose.yaml` initialising `booking_db`, ADR 0006 (inventory concurrency) and ADR 0007
  (provisional provisioning).

Out of scope for this phase, deliberately: `checkout`/`PaymentRequested` (Phase 4), waiting
room and Redis (Phase 5 UI), outbox and `EventPublished` consumption (Phase 3).

## Tasks

| ID | Task | Specs | Route | Status |
| -- | ---- | ----- | ----- | ------ |
| T1 | Booking module, config, compose `booking_db`, Flyway V1 | S1 | inline | done |
| T2 | Shared domain/web primitives and `CurrentUserProvider` | S9 | inline | done |
| T3 | Inventory entity, provisioning use case and internal endpoint | S2, S8 | inline | done |
| T4 | Availability endpoint | S3 | inline | done |
| T5 | Reservation aggregate, items and state rules | S4, S6 | inline | done |
| T6 | Atomic hold + idempotent create use case | S4, S5, S8 | inline | done |
| T7 | Read and cancel endpoints | S6 | inline | done |
| T8 | Expiration worker | S7 | inline | done |
| T9 | Domain, API, concurrency, idempotency and expiry tests | S8, S10 | inline | done |
| T10 | README, AGENTS, `.env.example`, ADRs, build and test run | S11, S12 | inline | done |

## Log

- **L1 (user request, verbatim):** `let's to implement the next phase`
- **L2 (phase identified):** `docs/06-roadmap-calidad.md` orders Phase 2 as Booking: inventory,
  reservations, expiration and idempotency, with the concurrency gate of 100 tickets. Phase 1
  delivered Catalog (34 tests green); `EVENTFLOW_HANDOFF.md` forbids implementing later phases
  in the same iteration.
- **L3 (provisioning bridge):** Booking inventory is created by `EventPublished` in Phase 3,
  which does not exist yet. Decision: an internal provisioning use case exposed over HTTP with
  an explicit item payload (no cross-service REST read), so Phase 3 only adds a listener
  calling the same method. Recorded in ADR 0007.
- **L4 (API verification):** Jackson 3 `JsonMapper` is the auto-configured bean in Boot 4 and
  `readValue`/`writeValueAsString` throw unchecked `JacksonException`
  (docs.spring.io/spring-boot/reference/features/json.html). `@ConfigurationPropertiesScan` binds
  the `booking.*` record; `@Scheduled(fixedDelayString = "${booking.expiry.interval}")` accepts
  the ISO-8601 duration. No new dependency was added: JPA native statements, `JdbcTemplate`
  (from spring-jdbc) and the existing Testcontainers modules cover the phase.
- **L5 (scope decisions):** `checkout`/`PaymentRequested` stay out (Phase 4), waiting room and
  Redis stay out (Phase 5), `EventPublished` stays out (Phase 3). The reservation state machine
  ships only `HELD -> CANCELLED` and `HELD -> EXPIRED`; the database `CHECK` and the enum widen in
  Phase 4 with a migration. `event_inventory` has no `version` column (ADR 0006 explains why).
- **L6 (verification evidence, real runs):**
  - `./gradlew :booking:test` — 40 tests, 0 failures: `BookingApiIntegrationTest` 16,
    `ReservationConcurrencyTest` 2, `ReservationExpiryTest` 4, `InventoryDomainTest` 5,
    `ReservationDomainTest` 13.
  - Concurrency gate: 100 capacity, 200 simultaneous requests → exactly 100 `201`, 100 `422`,
    `available=0 held=100 sold=0`, 100 reservations.
  - Idempotency under contention: 20 simultaneous requests with one key → 1 reservation,
    `held=1`.
  - `./gradlew build` — BUILD SUCCESSFUL (catalog untouched, its 34 tests still up to date).
  - Smoke test with the built jar against Compose PostgreSQL 18 (TTL `PT5S`): health UP,
    provisioning `201` and identical replay `200`, availability, reservation `201` (50.00),
    replay returns the same id, different payload `409 IDEMPOTENCY_KEY_REUSED`, over-hold `422
    INSUFFICIENT_TICKETS`, cancel `CANCELLED` + units back, second cancel `409`, expiry worker
    `EXPIRED` with units returned, other user `404`, `/v3/api-docs` and `/swagger-ui/index.html`
    `200`.
  - `docker/postgres/init/01-create-databases.sh` verified on a throwaway `postgres:18-alpine`
    volume: it creates `booking_db` after `POSTGRES_DB=catalog_db`.
- **L7 (provisioning side effect found in the smoke test):** identical provisioning replay
  returns `200` and does not touch rows; only a different offer is a `409`.
