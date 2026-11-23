# ADR 0006 — Inventory concurrency in Booking

- **Status:** accepted
- **Date:** 2026-11-23
- **Deciders:** tech lead

## Context

Booking owns ticket inventory. The product invariant is
`capacity = available + held + sold`, never negative, and two requests must never hold the last
unit. The specification (`docs/02-dominio-datos.md`) prescribes a conditional update instead of a
read followed by a write. That raises two questions this ADR settles:

1. Where does the invariant live if the statement, not the aggregate, moves the counters?
2. How are concurrent state transitions of one reservation handled?

## Decision

1. **One conditional statement per ticket type.** `EventInventoryRepository.hold` executes
   `UPDATE event_inventory SET available = available - :qty, held = held + :qty
   WHERE event_id = :eventId AND ticket_type_id = :typeId AND available >= :qty`.
   Zero updated rows means "no stock"; PostgreSQL re-evaluates the predicate under the row lock,
   so a stale read cannot oversell. `release` is the mirror statement guarded by
   `held >= :qty`.
2. **The database enforces the invariant.** A `CHECK` constraint
   (`available + held + sold = capacity`, all non-negative) makes an inconsistent move impossible
   even if application code is wrong. The aggregate still owns the reservation state machine.
3. **The aggregate does not own the counters.** A JPA entity with `@Version` plus retries would
   add a retry loop for the most contended path in the system. `EventInventory` is therefore a
   read model plus a `provision` factory; counters are moved only by the statements above.
4. **No `version` column on `event_inventory`.** The docs list one, but native statements bypass
   it, so a version field would give a false sense of protection. The conditional `WHERE` and
   the `CHECK` are the real controls.
5. **Deterministic lock order per reservation.** Multi-item holds sort by `ticket_type_id`
   before executing, so two reservations that overlap cannot deadlock.
6. **Reservations use optimistic locking.** `reservation.version` (`@Version`) makes two
   concurrent transitions of the same reservation conflict instead of overwriting each other;
   the loser receives `409 RESERVATION_CONCURRENTLY_MODIFIED` and reloads.
7. **Expiration claims with `FOR UPDATE SKIP LOCKED`.** The worker selects due rows with the
   claim, then changes the state and releases units in the same transaction, so any number of
   replicas can scan concurrently without releasing twice.

## Consequences

- The oversell path has no retry loop and no Redis lock; Redis never authorizes a hold.
- A failure inside the transaction rolls back both the reservation and the holds, so a failed
  request leaves no orphaned units and no idempotency key without a response.
- The invariant is tested twice: unit tests for the state machine and a concurrency test that
  runs 200 simultaneous requests against 100 units and asserts `available + held + sold = capacity`.
- Anyone changing a counter outside these statements is a review finding, because the `CHECK`
  will reject them at runtime.

## Alternatives considered

- **`SELECT ... FOR UPDATE` then `UPDATE` in the service:** correct but two round trips per line
  and easier to get wrong when a later line fails.
- **JPA optimistic locking with retries on inventory:** moves contention into the application and
  needs a bounded retry policy that the specification does not ask for.
- **Redis or a distributed lock:** explicitly rejected by `docs/04-frontend-redis.md`; Redis is
  ephemeral and must never decide availability.
