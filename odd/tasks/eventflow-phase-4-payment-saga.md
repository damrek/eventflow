# EventFlow — Phase 4 (Payment and Saga)

- **Objective:** the payment step of the booking saga: a Payment service that authorizes a
  simulated HTTP provider behind a circuit breaker, Booking checkout that requests payment,
  confirmation or compensation of the reservation, and the late-payment refund. Gate from
  `docs/06`: approved, rejected, timeout, duplicated event and late payment proven.
- **Branch:** `feat/payment-saga` (from `main` after PR #3).
- **Delivery strategy:** `ask-on-risk` (forecast ~2,600 authored lines → work-unit commits).
- **Test runner:** Gradle (`./gradlew :payment:test :payment-simulator:test :booking:test
  :notification:test`), JUnit 6, Testcontainers PostgreSQL and RabbitMQ.

## Specs

- **S1 — Payment module.** Gradle project `:payment` mapped to `services/payment`, own
  `payment_db`, Flyway as the only schema source, `spring.jpa.hibernate.ddl-auto=validate`,
  HTTP port `8084`. Acceptance: `./gradlew :payment:test` boots the context against real
  PostgreSQL and RabbitMQ containers.
- **S2 — HTTP provider simulator.** Gradle project `:payment-simulator` mapped to
  `services/payment-simulator`, port `8085`, `POST /simulator/authorizations` with
  `{paymentId, reservationId, amount, currency}`. Outcomes are deterministic from the amount so
  no shared mutable scenario state is needed: below `10.00` is rejected (simulated decline),
  cents `.98` answers `503` (provider error), cents `.99` waits
  `payment.simulator.timeout-delay` (default `5s`) before approving (client timeout), anything
  else is approved. It records nothing; it is a provider double, not a service.
- **S3 — Payment consumes `PaymentRequested`.** Queue `payment.payment-requested`, consumer name
  `payment.payment-requested`, routing key `payment.requested`. In one transaction the listener
  claims the inbox, keeps one `payment` row per reservation (`reservation_id` unique), asks the
  provider through `HttpPaymentGateway`, records one `payment_attempt` row per provider call and
  writes `PaymentAuthorized` (`payment.authorized`) or `PaymentRejected` (`payment.rejected`) to
  the outbox. A provider timeout or `5xx` is transient: the transaction rolls back, the inbox
  marker and the payment with it, and the message follows the retry queues to the DLQ, so nothing
  is finalized and no unit is ever authorized by a call nobody answered. A duplicate delivery
  produces one payment, one attempt and one result event.
- **S4 — Circuit breaker.** `HttpPaymentGateway` wraps the provider call in a Resilience4j
  `CircuitBreaker` (`resilience4j-circuitbreaker` 2.4.0, verified on Maven Central). Knobs live
  in `payment.provider.circuit-breaker.*` (sliding window `10`, minimum `5` calls, failure rate
  `50%`, open `10s`, three permitted half-open calls). While open, the call fails fast with
  `ProviderUnavailableException` and no attempt row is written, so the message is retried instead
  of hammering a dead provider. HTTP connect/read timeouts come from `spring.http.clients.*`
  (`2s`/`2s`).
- **S5 — Checkout.** `POST /api/reservations/{reservationId}/checkout` (owner only) moves
  `HELD -> PENDING_PAYMENT` when the hold has not expired and writes `PaymentRequested` in the
  same transaction. Payload `{reservationId, eventId, userId, amount, currency, items:
  [{ticketTypeId, quantity}]}` with `currency` `EUR` and `amount` the reservation total. Any
  other status, an expired hold or a second checkout answers `409 RESERVATION_NOT_PAYABLE` and
  writes no event. Migration V3 widens the status `CHECK` (`PENDING_PAYMENT`, `CONFIRMED`,
  `PAYMENT_FAILED`) and adds `payment_id`.
- **S6 — Booking consumes payment results.** Queue `booking.payment-result`, consumer
  `booking.payment-result`, routing keys `payment.authorized` and `payment.rejected`, with its
  own recoverer and container factory because the service now has two consumer queues. In one
  transaction it claims the inbox and then:
  - authorized on a live `PENDING_PAYMENT` reservation → `CONFIRMED`, units move
    `held -> sold` through a conditional statement, `BookingConfirmed` goes to the outbox;
  - authorized on an expired/cancelled reservation, or one whose deadline already passed →
    nothing is confirmed: the reservation expires, its units return to `available`, the payment
    id is recorded and `PaymentRefundRequested` (`payment.refund-requested`) is published as the
    compensation;
  - rejected on a live `PENDING_PAYMENT` reservation → `PAYMENT_FAILED`, units return to
    `available`, `BookingPaymentFailed` (`booking.payment-failed`) goes to the outbox;
  - a duplicate or already-settled result changes nothing.
- **S7 — Payment refund.** The same `payment.payment-requested` queue consumes
  `PaymentRefundRequested`: an `AUTHORIZED` payment becomes `REFUNDED` (simulated refund, no
  provider call); an already `REFUNDED` payment is a no-op; any other status is a permanent
  failure rejected to the DLQ.
- **S8 — Expiration covers payment.** The expiry worker claims `HELD` and `PENDING_PAYMENT`
  reservations (`HELD/PENDING_PAYMENT -> EXPIRED`, releasing units) and confirmation refuses a
  reservation whose deadline passed, so a late authorization can never sell released inventory.
- **S9 — Notification covers the failure.** `BookingPaymentFailed` is bound on
  `notification.booking-events`, stored as `BOOKING_PAYMENT_FAILED` with copy naming the
  reservation; an unknown event type still ends in the DLQ.
- **S10 — Gate tests.** (a) approved: checkout → authorized → `CONFIRMED`, `held` 0, `sold`
  grew, `BookingConfirmed` in the outbox. (b) rejected: `PAYMENT_FAILED`, units back in
  `available`, `BookingPaymentFailed` published. (c) timeout: the provider answers after the read
  timeout, the delivery spends the retry queues and ends in the DLQ; no payment row and no result
  event survive the rollback. (d) duplicate: the same `PaymentRequested` delivered twice yields one payment, one
  attempt and one result event; the same `PaymentAuthorized` delivered twice confirms once.
  (e) late payment: authorized after the hold expired leaves the reservation `EXPIRED`, releases
  nothing twice and publishes `PaymentRefundRequested`; Payment refunds it. (f) circuit breaker:
  repeated `503`s open the breaker and the next call fails fast without a provider attempt.
- **S11 — Documentation.** README (Payment and simulator start commands, saga flow), `AGENTS.md`
  (new modules, slices, packages), `.env.example`, `payment_db` in the PostgreSQL init script,
  ADR 0010 (saga, compensation, circuit breaker, simulator contract) and the two compensating
  events appended to the event table in `docs/03-apis-eventos.md`.

Out of scope: Redis and the waiting room (Phase 5), frontend, metrics/tracing (Phase 7), retries
inside the provider gateway (`Retry` decorator), refunds with a real provider call.

## Tasks

| ID | Task | Specs | Route | Status |
| -- | ---- | ----- | ----- | ------ |
| T1 | Payment module, simulator module, versions, `payment_db` | S1, S2 | inline | done |
| T2 | Payment domain, schema, gateway with circuit breaker, outbox | S3, S4 | inline | done |
| T3 | Payment consumer: `PaymentRequested` and `PaymentRefundRequested` | S3, S7 | inline | done |
| T4 | Booking checkout, status migration, `PaymentRequested` | S5 | inline | done |
| T5 | Booking payment-result consumer, confirm and compensation | S6, S8 | inline | done |
| T6 | Notification `BookingPaymentFailed` | S9 | inline | done |
| T7 | Gate tests: approved, rejected, timeout, duplicate, late, breaker | S10 | inline | done |
| T8 | README, AGENTS, env, init script, ADR 0010, event table | S11 | inline | done |

## Log

- **L1 (user request, verbatim):** `let's to implement the next phase`
- **L2 (phase identified):** `docs/06-roadmap-calidad.md` Phase 4 is Payment/Saga: Payment plus an
  HTTP simulator and a circuit breaker, gate "approved, rejected, timeout, duplicated event and
  late payment proven". Phases 0-3 are merged on `main`.
- **L3 (versions verified):** resilience4j-circuitbreaker `2.4.0` is the latest on Maven Central
  (metadata fetched from `repo1.maven.org`; the POM only needs `resilience4j-core` and
  `slf4j-api`), so no Spring Boot starter/first-party autoconfiguration is involved. Boot 4.2
  provides `spring-boot-starter-restclient` with an auto-configured `RestClient.Builder` and
  global `spring.http.clients.connect-timeout` / `read-timeout` properties (Boot reference).
- **L4 (decisions):** the simulator is a separate Boot app so the circuit breaker protects a real
  HTTP dependency rather than a self-call, and its outcomes are derived from the amount instead
  of a mutable scenario endpoint, which keeps every test deterministic without shared state. The
  provider call runs inside the consumer transaction: a transient failure rolls back the inbox
  claim, the payment and the outbox together, which is exactly the at-least-once contract. Late
  authorizations are compensated with `PaymentRefundRequested`, the event added by this phase
  (ADR 0010); `BookingPaymentFailed` extends the notification contract for the rejected branch.
- **L5 (implementation):** added `services/payment` (domain, schema, gateway, consumer, outbox)
  and `services/payment-simulator`; Booking gained checkout, `PENDING_PAYMENT`/`CONFIRMED`/
  `PAYMENT_FAILED`, the payment-result consumer and refund compensation; Notification gained
  `BOOKING_PAYMENT_FAILED`.
- **L6 (evidence, `./gradlew test --rerun-tasks`):** BUILD SUCCESSFUL, 113 tests — catalog 36,
  booking 55, notification 6, payment 12, payment-simulator 4. Gate tests:
  `PaymentConsumerTest` (approval, rejection, duplicate delivery, provider timeout through the
  retry queues to the DLQ, refund idempotence, unknown type to the DLQ),
  `PaymentGatewayCircuitBreakerTest` (five `503`s open the breaker; the next call fails fast
  without a provider attempt), `PaymentSagaIntegrationTest` (checkout and its `409`s, confirm and
  `held -> sold`, rejection and release, duplicate authorization, late payment compensated before
  and after the expiry worker, `PENDING_PAYMENT` expiry) and the new
  `NotificationConsumerTest` case for `BOOKING_PAYMENT_FAILED`. Payment talks to a real HTTP double
  (`FakePaymentProvider`) started once per JVM; no gateway is mocked.
- **L7 (problem found while proving the gate):** the Phase 3 retry path was dead code. Boot applies
  the `MessageRecoverer` bean only when listener retry advice is enabled (it was not), and counting
  `x-death` could not work because the broker replaces that header on the next dead-lettering: the
  timeout test looped in `retry.2` 358 times. Fixed by enabling stateless retry with
  `max-retries: 0`, writing `x-eventflow-retry-attempt` before republishing and rethrowing
  `AmqpRejectAndDontRequeueException`; ADR 0010 records the supersession and ADR 0009 is updated.
  A second consequence: Booking's two consumer queues needed an explicit container factory, since a
  second `MessageRecoverer` bean would have disabled both.
- **L8 (delivery):** work-unit commits on `feat/payment-saga`; push, PR and merge remain human
  decisions.
