# ADR 0008 — RabbitMQ over Kafka for the asynchronous integration

**Status:** Accepted (Phase 3 for the broker, recorded as a decision in Phase 8)

**Why it appears here:** the roadmap (`docs/06-roadmap-calidad.md`) lists "RabbitMQ vs Kafka" as one of
the decisions to document, and `docs/01-arquitectura.md` states the choice as a fact without saying
why. The mechanism built on top of the broker — Transactional Outbox, Inbox, retry queues and DLQ —
is ADR 0009; this record covers only the broker.

## Context

The system needs one asynchronous integration: Catalog announces `EventPublished`, Booking announces
reservation lifecycle events, Payment answers with authorizations and rejections, and Notification
builds the user's mailbox. `EVENTFLOW_HANDOFF.md` fixes the invariants that the transport must
respect:

1. PostgreSQL is the source of truth; a broker never owns business state.
2. Delivery is at-least-once and consumers are idempotent; exactly-once is not promised.
3. A broker outage must delay events, never lose them, and a permanent failure must end in a DLQ.

The volume is a portfolio system: five services, a handful of queues, messages that are small JSON
envelopes, and one Docker host. The interesting problems are correctness under duplicate delivery and
under broker outage, not throughput.

## Decision

**RabbitMQ 4 (AMQP 0.9.1, through `spring-boot-starter-rabbitmq`) is the broker.** One durable topic
exchange (`eventflow.events`), one dead-letter exchange, one durable queue per consumer plus that
consumer's three retry queues with TTLs, publisher confirms on every publish, and the outbox row
committed in the same transaction as the business change (ADR 0009).

## Alternatives considered

- **Kafka.** It is the default answer for "event-driven", and it was rejected for reasons that hold
  in this repository, not because it is worse in general:
  - The features that justify Kafka are absent here: no stream processing, no replay of a topic to
    rebuild a projection (each service's state is rebuilt from its own migrations + events it
    consumed, not by rewinding a log), no partition-ordering requirement beyond "one reservation's
    events are handled in order", which one queue per consumer already gives, and no throughput need
    that was measured.
  - Its operations are heavier than the lesson: a broker cluster, partitions, consumer groups, and a
    retention model to reason about, on a laptop-sized Compose stack.
  - Per-message delayed retry and a per-queue DLQ are native in RabbitMQ (TTL queue + dead-letter
    routing) and need extra tooling in Kafka, which is exactly the retry topology ADR 0009 relies on.
  - The one Kafka property that is genuinely lost is the replayable log. This system does not need
    it, and pretending to have it by keeping events forever would be a claim without a test.
- **Redis Streams.** Redis is already in the stack, but as a *throttle* (waiting room, admission
  tokens, rate limits) whose failure mode is documented as "fail open, never decide stock"
  (ADR 0011). Making the same Redis instance the messaging backbone would turn a cache outage into a
  lost-event outage and would break the failure isolation the waiting-room design depends on.
- **A cloud queue (SQS, Pub/Sub).** Out of scope: the project runs locally with Docker and no cloud
  services (`docs/01-arquitectura.md`), and credentials in CI are explicitly avoided.

## Consequences

- Routing keys give per-consumer queues without a schema registry: a new consumer is a new queue and
  binding, which is why Notification could be added without touching Catalog.
- Retry and DLQ semantics are configured per queue (TTLs of `5s`, `15s`, `45s`, then `queue.dlq`),
  and the attempt counter travels in a header the recoverer writes (`x-eventflow-retry-attempt`)
  because the broker replaces `x-death` on republish (ADR 0009).
- The broker is a single container in Compose and a single StatefulSet in k3d: there is no failover
  story, and the README says so. Durability comes from the outbox plus publisher confirms, not from
  broker replication.
- Ordering is per queue and per consumer. Nothing in the domain requires a global order.

## When this decision should be revisited

If the system ever needs to replay history to rebuild a projection, to fan out one event to hundreds
of independent consumers, or to sustain a throughput that a single broker cannot take — each of which
would be a *measured* requirement, not an anticipated one — Kafka becomes the better fit. Until then,
RabbitMQ keeps the failure modes small enough to prove with tests.
