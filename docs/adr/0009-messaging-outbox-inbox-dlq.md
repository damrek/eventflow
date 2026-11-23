# ADR 0009 — Asynchronous integration: Transactional Outbox, Inbox and per-queue DLQ

- **Status:** accepted
- **Date:** 2026-11-23
- **Deciders:** tech lead
- **Supersedes:** ADR 0007 (the provisional inventory trigger)

## Context

Phase 3 turns the services into an event-driven system: Catalog announces `EventPublished`,
Booking provisions inventory from it and publishes reservation lifecycle events, and Notification
builds the user's mailbox from those. The non-negotiable invariants are in
`EVENTFLOW_HANDOFF.md`: PostgreSQL is the source of truth, each service owns its database, events
travel through a Transactional Outbox, delivery is at-least-once and consumers are idempotent.

Two failure modes have to be provable, not promised: a broker outage must not lose an event, and a
duplicate delivery must not duplicate its effect. The quality gate of `docs/06-roadmap-calidad.md`
names exactly those two.

## Decision

1. **One durable topic exchange** (`eventflow.events`) with routing keys per event type
   (`event.published`, `booking.cancelled`, `booking.expired`, …). One durable dead-letter
   exchange (`eventflow.events.dlx`).
2. **Producers write a `outbox_event` row in the business transaction.** The row stores the full
   versioned envelope (`eventId`, `eventType`, `eventVersion`, `occurredAt`, `aggregateId`,
   `correlationId`, `payload`) exactly as it will be published. A business change without its
   event, or an event without its business change, is impossible by construction.
3. **A relay publishes the outbox.** It claims pending rows with `FOR UPDATE SKIP LOCKED`, sends
   them with publisher confirms enabled and calls `waitForConfirmsOrDie`; only a confirmed batch
   gets `published_at`. An unconfirmed publish rolls back, so the row stays pending and the next
   tick retries. Several replicas can drain different batches without blocking each other.
4. **Consumers keep an inbox.** `processed_message(consumer_name, event_id)` is inserted with
   `on conflict do nothing` in the same transaction as the business effect. A duplicate delivery
   finds the marker and does nothing; a rollback removes both, so a retry repeats the whole
   effect. The unique key is also the arbiter between two deliveries processed concurrently.
5. **Bounded retries with backoff, then a DLQ.** Each business queue has one retry queue per
   configured delay (`5s`, `15s`, `45s` by default, `eventflow.messaging.retry.delays`). A failing
   delivery is republished to `<queue>.retry.N`, whose message TTL returns it to the business
   queue; the attempt number travels in the `x-eventflow-retry-attempt` header the recoverer writes
   (ADR 0010 supersedes the original `x-death` counting), so it survives restarts and is shared by
   replicas. After the last attempt the message is rejected without requeue and the business
   queue's dead-letter arguments move it to `<queue>.dlq`. Boot's listener retry advice is enabled
   with `max-retries: 0` and `stateless: true`, so one delivery means one handler attempt and the
   backoff lives in the broker.
6. **Permanent failures skip the retries.** A malformed envelope, an event type the consumer does
   not handle, or an `eventVersion` this build cannot read is rejected immediately: retrying would
   only delay the same DLQ outcome.
7. **ACK after the consumer transaction.** Listeners are transactional and the container
   acknowledges after the method returns, i.e. after the commit. `default-requeue-rejected` stays
   `false`; nothing is requeued infinitely.
8. **Raw JSON messages, explicit parsing.** Producers send a pre-serialized envelope with
   `content-type: application/json` and consumers parse it with the Boot `JsonMapper`. Spring AMQP's
   type headers (`__TypeId__`) are not used, so no Java class name travels over the wire.
9. **The provisional HTTP provisioning endpoint is deleted** (ADR 0007). Inventory is provisioned
   only by the `EventPublished` consumer, which calls the same `InventoryService.provision` use case
   inside its inbox transaction.
10. **Each service owns its copy of the messaging primitives** (envelope, outbox, relay, inbox,
    topology, recoverer), next to its existing copies of `shared/domain` and `shared/web`. Sharing a
    library with JPA entities would couple the services' schemas, which the architecture forbids.

## Consequences

- A broker outage delays events; it never loses them: they sit in `outbox_event` until a publish is
  confirmed. A stopped Kafka-style consumer does not block producers.
- Duplicates are expected and harmless: the inbox makes every consumer idempotent, and
  `notification.event_id` is unique as a second line of defence.
- Retry delays are queue arguments declared at startup. Changing them on an existing broker needs
  the queues recreated (delete them, or reset the volume in local development); a message already
  sitting in a retry queue keeps its TTL.
- In-flight retries do not block the consumer: the message waits in its retry queue and the
  prefetch slot is free.
- The DLQ is a manual/operational surface in this phase: messages are visible in the management UI
  (`http://localhost:15672`) and are replayed by hand. Automatic replay is out of scope.
- Because each service parses the envelope it consumes, a producer can add payload fields without
  breaking a consumer, and a consumer must bump `eventVersion` for a breaking change.
- `docs/03-apis-eventos.md` remains the contract; `PaymentRequested`, `PaymentAuthorized`,
  `PaymentRejected`, `BookingConfirmed`, `PaymentRefundRequested` and `BookingPaymentFailed` were
  added by Phase 4 (ADR 0010) without changing this design.

## Alternatives considered

- **Publish directly from the business transaction to the broker.** Loses events when the broker is
  down or the commit fails after publishing, and cannot be tested for the outage gate.
- **Publish inside the business transaction and let the broker call ACK the transaction.** Requires
  broker-integrated transactions and still does not remove the need for idempotent consumers.
- **In-process retries (`RetryInterceptorBuilder`) instead of retry queues.** Simpler, but blocks a
  consumer thread while backing off and loses the attempt history on restart.
- **Exactly-once delivery.** Not offered by RabbitMQ; at-least-once plus an inbox is the honest
  contract and is what the roadmap asks for.
- **A shared messaging library with the outbox entity.** Would share a JPA entity across databases
  and couple three services' schemas to one release.
