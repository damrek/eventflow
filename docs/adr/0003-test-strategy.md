# ADR 0003 — Test strategy: real PostgreSQL, no in-memory substitutes

- **Status:** accepted
- **Date:** 2026-11-22
- **Deciders:** tech lead

## Context

The roadmap requires integration tests with Testcontainers and explicitly forbids replacing
PostgreSQL, RabbitMQ or Redis with mocks in integration tests. Phase 1's quality gate is
"CRUD/publication and tests with real PostgreSQL".

## Decision

1. **Integration tests run against a real PostgreSQL container** started by Testcontainers
   (`postgres:18-alpine`) and wired with `@ServiceConnection`. The container image matches
   the Compose service so schema, SQL types and constraints behave identically.
2. **H2 is not a dependency of this project**, not even for convenience tests. H2 would
   accept SQL that PostgreSQL rejects and hide exactly the defects this project is about.
3. **Flyway migrations are the only schema source.** `spring.jpa.hibernate.ddl-auto=validate`
   makes every context load a schema/entity consistency check.
4. **Tests isolate their data** by truncating Catalog tables before each test
   (`@Sql(..., BEFORE_TEST_METHOD)`), so assertions on counts are deterministic without
   hiding ordering bugs behind `@Transactional` rollback that never exercises the real
   commit path.
5. **Unit tests cover domain rules** (state transitions, capacity invariants) without a
   Spring context; integration tests cover the HTTP contract, persistence and migrations.
6. **Docker is a hard prerequisite** for `./gradlew build`. This is documented in the README
   instead of being silently skipped when Docker is absent: a skipped integration test is a
   false green.

## Consequences

- CI needs a Docker-capable runner from Phase 0 onward; this is compatible with GitHub
  Actions hosted runners.
- The first test run pulls the PostgreSQL image; subsequent runs reuse it.
- Later phases extend the same approach with RabbitMQ and Redis containers instead of
  replacing them with mocks.

## Alternatives considered

- **H2/HSQLDB with PostgreSQL compatibility mode:** rejected, it hides real SQL behavior.
- **Testcontainers with `@Transactional` rollback per test:** kept out; the HTTP layer
  commits, so rollback-based isolation would not describe the production path.
