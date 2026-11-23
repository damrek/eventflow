# ADR 0011 — Redis waiting room, admission tokens and distributed rate limiting

**Status:** Accepted (Phase 5)

## Context

Phase 5 adds the virtual waiting room described in `docs/04-frontend-redis.md`: users join an
event's queue, see their position and are admitted in batches; an admission token with a TTL lets
an admitted user reserve; an expired or invalid token must not skip an active wait; and the rate
limit on join and reservation creation has to work across replicas. Redis is the only shared,
low-latency store available, but it is not durable and must never become the authority on stock.

The project invariant is explicit: PostgreSQL decides availability, retention, sale and expiry; a
Redis restart may lose the queue but must never duplicate or oversell inventory.

## Decision

1. **Redis is a throttle, not an authority.** The waiting room decides *when* a user may try to
   reserve, never *whether a unit exists*. The hold remains one conditional `UPDATE` in PostgreSQL
   (ADR 0006). This is the reason every failure mode of Redis degrades the queue instead of the
   sale.
2. **Four key families, one registry.** Queue `waiting:{eventId}` (sorted set of userIds scored by
   a server-assigned sequence), sequence `waiting-seq:{eventId}` (monotonic `INCR`, no client
   clock), admission `waiting-token:{eventId}:{userId}` (string with TTL) and registry
   `waiting-events` (set of events whose queue is non-empty). The registry replaces `KEYS`/`SCAN`
   in the dispatcher: it only visits events that actually have waiters, and empty queues are
   removed from it atomically.
3. **Join and admission are Lua scripts.** Join inserts once (`ZRANK` first, so a rejoin keeps the
   position), assigns the next sequence and registers the event. Admission pops up to a batch in
   order, writes one token per popped member with `PX` and deletes the queue plus its registry
   entry when it drains. Two replicas racing pop disjoint members, so nobody is admitted twice and
   no position is served twice. Both scripts return an integer, avoiding multi-bulk serializer
   semantics.
4. **Admission is driven by real capacity.** A scheduled dispatcher admits a batch per event only
   when Booking's PostgreSQL inventory for that event has `available > 0`. A sold-out or not yet
   provisioned event admits nobody; units released by expiry or cancellation let the queue move
   again. The batch size and cadence are configuration (`booking.waiting-room.*`), and a public
   `dispatch()` lets tests drive admission without sleeping.
5. **The token is required only while a queue exists.** `POST /api/reservations` accepts
   `X-Admission-Token`; while `waiting:{eventId}` exists, a missing, expired or foreign token
   answers `403 NOT_ADMITTED`. Without a queue there is no wait to skip, so the gate is off and the
   existing API keeps working for events nobody queued for. Tokens are bound to `(event, user)` and
   are not consumed by a reservation: an idempotent retry with the same `Idempotency-Key` must
   remain valid until the TTL expires. An expired token sends the user back to the tail on the next
   join; a live rejoin keeps the position.
6. **Rate limiting is one atomic increment per subject and endpoint.** Fixed window with
   `INCR` + `PEXPIRE` in a Lua script under `rate:{endpoint}:{userId}`. The window lives only in
   Redis, so every replica shares it; the key includes the subject, so one abusive user does not
   consume another's budget. Over the limit answers `429` with `Retry-After` (seconds) and problem
   code `RATE_LIMITED`. Join defaults to 60 per minute and reservation creation to 300, leaving
   ordinary use untouched; the Phase 2 concurrency gate overrides the reservation limit to 10000
   because its 200-request burst tests inventory atomicity, not the throttle.
7. **Fail open on Redis failure, 503 only for the room itself.** Join and status answer
   `503 WAITING_ROOM_UNAVAILABLE` when Redis cannot be read: pretending a user has a position would
   be wrong. The admission gate and the rate limiter instead log a warning and let the request
   through: a Redis outage must not stop sales that PostgreSQL can still serve correctly, and the
   frontend offers a direct path to the reserve page. This is exactly the documented degradation
   ("maybe the queue is lost, never duplicated or oversold inventory").
8. **Redis is ephemeral in local infrastructure.** The compose service runs `redis:8-alpine` with
   `--save "" --appendonly no` and no volume. Losing the queue on restart is expected and proven by
   a Testcontainers test that stops and restarts the pinned container: the room answers 503, a
   reservation still succeeds without a token, the counters (`available + held + sold`) hold, and
   the old position is gone after the restart.

Out of scope: catalog caching in Redis (not part of the Phase 5 acceptance criteria), Redis
Streams (RabbitMQ is the only broker), Redis locks (PostgreSQL resolves stock) and token bucket
(the fixed window is the MVP shape named by the spec).

## Consequences

- A user can join, poll a server-assigned position and be admitted; the frontend flow is proven by
  a real two-instance rate-limit test and by the SPA's own tests.
- An expired or foreign token cannot skip an active wait, and a token cannot be replayed by
  another user because the token key is per user.
- A queue whose members never return is bounded by a 24 h TTL; the registry is pruned by the
  dispatcher.
- Redis being a single point of *throttling* is acceptable: if it disappears the system keeps
  selling correctly, just without a queue, which is the documented trade-off.
- The gate is a fairness/load mechanism, not an authorization system. It is not presented as
  authentication and cannot be, with the demo `X-User-Id` identity.

## Alternatives considered

- **Redis lock or counter as the stock authority.** Rejected by the project invariants: a lost key
  would oversell or block sales, and PostgreSQL already provides the conditional update that
  proves `capacity = available + held + sold`.
- **A token consumed by the reservation.** Simple to reason about, but it would reject an
  idempotent retry after a network error and force the user back into the queue; TTL plus the
  idempotency row already bound the effect.
- **Gate always on.** Would require every existing reservation test and client to join a room even
  when no queue exists, and would turn a load-shedding mechanism into a hard dependency.
- **`KEYS`/`SCAN` in the dispatcher.** `KEYS` blocks Redis and `SCAN` walks the whole keyspace for
  a handful of active events; the registry set is O(active events).
- **Rate limiting in every endpoint and in memory per replica.** In-memory counters would not be
  shared ("two instances" is a gate) and limiting checkout/cancel would punish the user for a
  legitimate retry; only join and reservation creation are named by the spec.
- **Redis persistence (AOF/RDB).** Adds an operational story the MVP does not need and would
  blur the "ephemeral" contract the correctness argument relies on.
