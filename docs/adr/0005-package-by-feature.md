# ADR 0005 — Package-by-feature layout inside each service

- **Status:** accepted
- **Date:** 2026-11-22
- **Deciders:** tech lead

## Context

EventFlow already separates services by deployable and database. Inside Catalog, the original
layout was layer-first: `domain/`, `application/`, `api/`. That reads well with one aggregate,
but every new behavior ends up scattered across three directories, and `CatalogService`
mixed two aggregates in one class. The project requested a layout where each domain object
lives in its own package together with its collaborators.

## Decision

1. **Slices per aggregate at the service root.** `venue/`, `event/`, plus `shared/` and
   `config/`. A slice owns its entity, value objects, repository, service, controller and
   `dto/` subpackage.
2. **`TicketType` stays inside `event/`.** It is part of the Event aggregate: `Event.draft`
   enforces capacity, price and name uniqueness across its ticket types. Moving it to its own
   package would split the aggregate and make those invariants unenforceable.
3. **`shared/` holds primitives only:** domain exceptions, `DomainChecks`, the RFC 9457
   handler, the correlation filter and the page envelope. No business rule may live there;
   that is a review finding.
4. **Cross-slice dependencies are allowed only to reference another aggregate.** `EventService`
   depends on `VenueRepository` because an event references a venue. It must not call
   `VenueService` business methods.
5. **One service per aggregate.** `VenueService` and `EventService` replace the former
   `CatalogService`, which mixed two aggregates in one class.
6. **No command DTOs for now.** Controllers pass primitives and the domain value object
   `TicketTypeSpec`; a record per endpoint was indirection without benefit at this size.
7. **Mapping lives with the response DTO** (`VenueResponse.from`, `EventDetailResponse.from`).
   Keeping it explicit means an entity change cannot silently alter the HTTP contract.
8. **Tests mirror the slices.** Domain tests live in `event/` and `venue/`; the single
   `CatalogApiIntegrationTest` stays at the service root because it exercises the cross-slice
   flow venue → event → publish.

## Consequences

- Adding an aggregate means adding a package, not editing three layer directories.
- Package-private collaborators stay possible inside a slice (`TicketType.create`).
- `shared/` is the natural dumping ground; the ADR limits what may enter it.
- The HTTP contract is unchanged: the same 34 tests pass without modifying a single
  assertion, which is the evidence that this was a structural refactor.

## Alternatives considered

- **Layer-first (`domain`/`application`/`api`):** rejected; it hides aggregate boundaries and
  scatters each behavior across directories as the service grows.
- **One package per class with its own service:** rejected; `TicketType` is not an
  independent aggregate and would break the capacity invariant.
- **A `common` Gradle module shared between services:** rejected in ADR 0001; services must
  not share code or entities.
