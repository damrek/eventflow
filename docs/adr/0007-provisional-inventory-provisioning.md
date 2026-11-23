# ADR 0007 — Provisional inventory provisioning before `EventPublished`

- **Status:** accepted (superseded in Phase 3 by the `EventPublished` consumer)
- **Date:** 2026-11-23
- **Deciders:** tech lead

## Context

Phase 2 delivers Booking with inventory, but the designed trigger for that inventory is the
`EventPublished` event published by Catalog (`docs/03-apis-eventos.md`), which belongs to Phase 3
(RabbitMQ, Outbox/Inbox). Without inventory, Phase 2 cannot be exercised or tested, and the
phase gate (100 units, no oversell) cannot be proven.

The architecture also forbids Booking from reading Catalog's database and from treating
eventually-consistent replication as anything other than events.

## Decision

1. Booking exposes **one provisioning use case**, `InventoryService.provision(eventId, items)`,
   and a provisional HTTP trigger `POST /api/internal/inventory/events/{eventId}` with an explicit
   payload `{items:[{ticketTypeId,price,capacity}]}`.
2. The endpoint lives under `/api/internal`, outside the public reservation contract.
3. Provisioning is idempotent by offer: the same payload returns `200` with the current
   availability and creates nothing, a different payload returns
   `409 INVENTORY_ALREADY_PROVISIONED`. Capacity can never change once units may be held.
4. Phase 3 deletes the HTTP trigger and adds an `EventPublished` listener that calls the same use
   case; no domain code changes. The listener must be idempotent for the same reason (at-least-once
   delivery), which this decision already enforces.
5. Booking does **not** call Catalog over REST in this phase: a synchronous cross-service read
   would be a dependency the final architecture does not have.

## Consequences

- Phase 2 is runnable and testable end to end while the broker does not exist.
- The internal endpoint must not be exposed beyond the local/demo environment; it is not
  authenticated because the whole MVP runs locally behind a trusted proxy.
- The availability read (`GET /api/events/{eventId}/availability`) is unaffected by the eventual
  replacement: it always reads PostgreSQL.

## Alternatives considered

- **Booking reads `GET /api/events/{eventId}` from Catalog:** adds a synchronous dependency and
  configuration, and would be removed in Phase 3 anyway.
- **Seed inventory by SQL script:** bypasses the use case and the tests that prove idempotency.
- **Implement Outbox and RabbitMQ now:** violates the phase order in `docs/06-roadmap-calidad.md`
  and `EVENTFLOW_HANDOFF.md`.
