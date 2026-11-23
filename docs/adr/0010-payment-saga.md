# ADR 0010 — Payment saga, late-payment compensation and provider circuit breaker

**Status:** Accepted (Phase 4)

## Context

Phase 4 adds the payment step of the booking saga (`docs/06-roadmap-calidad.md`). Payment and
reservation cannot share an ACID transaction: they are different services with different databases,
and the provider is an external system that can approve, reject, time out or be down. The roadmap
asks for a simulated HTTP provider, a circuit breaker, and proof of approved, rejected, timeout,
duplicated event and late payment. It also asks for a defined compensation when a payment is
authorized after the hold expired.

## Decision

1. **One logical payment per reservation.** `payment.reservation_id` is unique; the row is created
   by the consumer that receives `PaymentRequested` and moves
   `PENDING → AUTHORIZED | REJECTED`, with `AUTHORIZED → REFUNDED` as the only compensation.
   Settled payments never change again, so a duplicated provider result or refund cannot move money
   twice. The provider request carries the payment id as its idempotency handle.
2. **Checkout is the only entry to payment.** `POST /api/reservations/{id}/checkout` moves
   `HELD → PENDING_PAYMENT` and writes `PaymentRequested` in the same transaction; any other state,
   an expired hold or a second checkout answers `409 RESERVATION_NOT_PAYABLE`. Cancellation stays
   available only while `HELD`, because a hold that already started a payment cannot be released
   without deciding what happens to that payment.
3. **The provider call runs inside the consumer transaction.** Inbox claim, payment row, attempt row
   and result event commit or roll back together. A timeout, `5xx`, connection error or open circuit
   throws a transient `ProviderUnavailableException`: the transaction rolls back (nothing is
   finalized) and the broker retry queues try again later. A malformed payload or an illegal
   transition is permanent and goes straight to the DLQ. The cost is holding a database transaction
   during an HTTP call; the benefit is that no payment can be half-written, and the at-least-once
   contract needs no separate reconciliation step in this phase.
4. **The circuit breaker is a plain Resilience4j core object.** `resilience4j-circuitbreaker`
   2.4.0 (verified on Maven Central) is used programmatically with our own
   `payment.provider.circuit-breaker.*` properties; the Spring Boot starter targets the Boot 3
   auto-configuration model and is not involved. HTTP timeouts come from Boot 4's
   `spring.http.clients.*`, so the simulator's slow answer produces a real client timeout. While the
   breaker is open the gateway fails fast and writes no attempt row: the message waits in its retry
   queue instead of hammering a dead provider.
5. **The provider is a separate simulator application.** `:payment-simulator` is a real HTTP server
   on its own port, so the gateway, its timeouts and the breaker protect a genuine remote call rather
   than an in-process shortcut. Its outcome is derived from the amount (below `10.00` rejects,
   cents `.98` answers `503`, cents `.99` answers late, anything else approves), which keeps every
   test and demo deterministic without shared mutable scenario state. It owns no database and
   publishes no events.
6. **Confirmation and rejection are conditional inventory moves.** `CONFIRMED` moves
   `held → sold` with one guarded `UPDATE`; `PAYMENT_FAILED` returns `held → available`; both are
   atomic with the reservation transition and the outbox event. The expiry worker now claims
   `HELD` and `PENDING_PAYMENT`, and confirmation refuses a reservation whose deadline passed.
7. **A late authorization is compensated, never honored.** If the authorization arrives for an
   expired, cancelled or already failed reservation — or for a `PENDING_PAYMENT` whose deadline
   passed before the worker ran — Booking expires and releases when needed, records the payment id
   and publishes `PaymentRefundRequested`; Payment moves the payment to `REFUNDED` (simulated, no
   provider call). This is the compensation the roadmap requires and the reason the event table
   grows by one event. `BookingPaymentFailed` is likewise added so the user learns about a rejected
   payment through the mailbox.
8. **Retry accounting moved from `x-death` to an explicit header (supersedes a detail of
   ADR 0009).** Republishing a message keeps its `x-death` header, and the broker replaces that
   header on the next dead-lettering, so counting it produced an endless `retry.2` loop. The
   recoverer now writes `x-eventflow-retry-attempt` before republishing; the value survives the
   retry queue, a broker restart and several replicas. Boot's retry advice is enabled with
   `max-retries: 0` and `stateless: true`, so each delivery is attempted once and the recoverer owns
   the backoff. A permanent `AmqpRejectAndDontRequeueException` is rethrown by the recoverer, which
   keeps malformed envelopes and unknown versions on the direct path to the DLQ. Because Booking now
   has two consumer queues, its payment-result listener gets an explicit container factory with its
   own recoverer.

## Consequences

- The saga is at-least-once end to end: duplicates are absorbed by the inbox, and the payment state
  machine refuses a second settlement.
- A provider outage delays payments instead of losing or duplicating them; after the last retry the
  message is in `payment.payment-requested.dlq` and the payment remains unfinalized, which is
  visible and replayable by hand.
- A payment authorized after expiry leaves a refunded payment and a `PaymentRefundRequested`
  event in the outbox; the reservation never sells released inventory.
- The provider simulator is not a business service and is not deployed as one; Phase 6 will decide
  whether it ships in the local compose stack or only runs for demos and tests.
- The circuit breaker is per process. Two replicas keep independent windows; that is acceptable
  because each of them only protects its own provider calls.
- `payment_attempt` records only provider answers, not timeouts: a rolled-back attempt leaves no
  row by design. Logs carry the transient failures with their `traceId`.

## Alternatives considered

- **Synchronous payment inside `POST /checkout`.** Simpler to read, but it couples the user's request
  to provider latency and availability, and a timeout would leave the reservation in an unknown
  state with no durable request to retry.
- **Two-phase authorization/capture.** More faithful to real providers, but the roadmap only needs
  approve/reject/timeout and a late-payment compensation; a capture step would add state without
  adding evidence.
- **Recording the attempt before the provider call.** Would keep a row for timeouts, but the row
  would have to be written in a separate transaction, breaking the "inbox and effect commit
  together" rule that makes retries safe.
- **Treating an authorization after expiry as a confirmation.** Sells units that were already
  returned to availability and can break `available + held + sold = capacity`.
- **Deriving retries from `x-death`.** Attempted; the broker replaces the header on the next
  dead-lettering, which produced an infinite retry loop (see decision 8).
- **Resilience4j Spring Boot starter.** Targets Boot 3 auto-configuration; the core module plus
  explicit configuration is smaller and version-independent.
