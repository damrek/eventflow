# ADR 0002 — Catalog publication model and capacity invariant

- **Status:** accepted
- **Date:** 2026-11-22
- **Deciders:** tech lead

## Context

Catalog owns venues, events, ticket types and publication (`catalog_db`). The specification
requires `POST /api/events/{eventId}/publish` to "publish and initialize inventory via an
event" and requires per-event ticket types with prices and initial capacity. It does not
define how venues are created, when an event becomes visible to the public, or what happens
when published ticket capacity exceeds the venue capacity.

## Decision

1. **Venue CRUD is part of Catalog.** Events reference a venue; without a creation endpoint
   the only alternative would be seeding data, which hides a real aggregate relationship.
   `POST /api/venues` and `GET /api/venues` are added.
2. **Explicit state machine.** `DRAFT` → `PUBLISHED`, one transition, no rollback.
   Publishing twice returns `409 Conflict` with code `EVENT_ALREADY_PUBLISHED` instead of
   silently succeeding; a silent success would make the future `EventPublished` event
   ambiguous for Booking inventory initialization.
3. **Public listing returns only `PUBLISHED` events.** `GET /api/events/{id}` also returns
   drafts, because the demo admin flow needs to read what it created.
4. **Capacity invariant: sum(ticketType.capacity) ≤ venue.capacity.** Enforced when the
   event aggregate is created and re-checked at publish. The venue capacity is the physical
   limit; overselling it is the same class of error the Booking phase must prevent with SQL.
5. **Publish preconditions:** the event must have at least one ticket type and `startsAt`
   must be strictly in the future. Publishing an event without tickets would produce an
   `EventPublished` message that Booking cannot turn into inventory.
6. **Ticket type names are unique per event** (domain check and DB unique constraint), so
   inventory rows keyed by `(event_id, ticket_type_id)` stay unambiguous.
7. **Money is `NUMERIC(12,2)`** with `price >= 0`, validated at the API boundary with
   `@Digits(integer = 10, fraction = 2)`. No currency column yet: the MVP is single-currency
   and adding an unused column would be speculative.
8. **Pagination response is an explicit envelope** (`content`, `page`, `size`,
   `totalElements`, `totalPages`) instead of serializing Spring's `Page`, whose JSON shape
   is not a stable contract.

## Consequences

- Phase 3 (`EventPublished`) will read this state: only a `PUBLISHED` event produces the
  outbox record, so the state machine is the gate for inventory creation.
- The capacity invariant is enforced in the aggregate, so future admin endpoints cannot
  bypass it through a different code path.
- `GET /api/events` semantics (published only) are documented in OpenAPI and tested; the
  frontend in Phase 5 can rely on it.

## Alternatives considered

- **Publish as a boolean flag:** rejected; it permits invalid transitions and gives Booking
  no clear point in time to initialize inventory.
- **Venue as free text on the event:** rejected; it would duplicate venue data and lose the
  physical capacity limit that makes the invariant meaningful.
- **Sum capacity check only at the API layer:** rejected; the invariant belongs to the
  aggregate so every caller is protected.
