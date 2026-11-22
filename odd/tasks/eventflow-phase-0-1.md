# EventFlow — Phase 0 (bootstrap) + Phase 1 (Catalog)

- **Objective:** reproducible monorepo bootstrap and a working Catalog service (REST, JPA,
  Flyway, OpenAPI) on real PostgreSQL.
- **Branch:** `feat/catalog` (bootstrap committed on `main` first).
- **Delivery strategy:** `ask-on-risk` (forecast < 400 authored lines for bootstrap slices;
  Catalog is reviewed as one work unit).
- **Test runner:** Gradle (`./gradlew build`), JUnit 6, Testcontainers PostgreSQL.

## Specs

- **S1 — Monorepo build.** Gradle multi-project with committed wrapper (9.9.2), Java 21
  toolchain, version catalog for versions, one project per service under `services/`.
  Acceptance: `./gradlew build` from a clean clone.
- **S2 — Local infrastructure.** `compose.yaml` with a `core` profile providing PostgreSQL 18
  (`postgres:18-alpine`), healthcheck, named volume, and `.env.example`. Acceptance:
  `docker compose --profile core up -d` and `pg_isready` healthy.
- **S3 — Catalog schema.** Flyway `V1__create_catalog_schema.sql` creating `venue`, `event`,
  `ticket_type` with FK, `CHECK` constraints and unique `(event_id, name)`.
  `spring.jpa.hibernate.ddl-auto=validate`.
- **S4 — Venues.** `POST /api/venues` → `201`; `GET /api/venues` → list.
- **S5 — Create event.** `POST /api/events` with `name`, `venueId`, `startsAt`, `ticketTypes`
  → `201`, status `DRAFT`.
- **S6 — Public listing.** `GET /api/events?from=&to=&page=&size=` returns only `PUBLISHED`
  events, page envelope `content/page/size/totalElements/totalPages`, ordered by `startsAt`.
- **S7 — Event detail.** `GET /api/events/{eventId}` returns event plus ticket types.
- **S8 — Publication.** `POST /api/events/{eventId}/publish` → `DRAFT` to `PUBLISHED`;
  second call → `409` `EVENT_ALREADY_PUBLISHED`; sum of ticket capacity must not exceed
  venue capacity (`EVENT_CAPACITY_EXCEEDED`, `422`); event must have ticket types and a
  future `startsAt`.
- **S9 — Errors.** RFC 9457 `application/problem+json` with `type`, `title`, `status`,
  `detail`, `instance`, `code`, `traceId`; `X-Request-Id` echoed and reused as `traceId`.
- **S10 — Tests.** Domain unit tests plus integration tests against a real PostgreSQL
  container (Testcontainers 2.0.5). No H2 anywhere.
- **S11 — OpenAPI.** `/v3/api-docs` and `/swagger-ui.html` served by springdoc 3.2.1.
- **S12 — Documentation.** README with quick start, ADRs 0001-0003, specification docs
  copied into the repository.

## Tasks

| ID | Task | Specs | Route | Status |
| -- | ---- | ----- | ----- | ------ |
| T1 | Gradle wrapper, settings, root build, version catalog | S1 | inline | done |
| T2 | Compose `core` profile, `.env.example`, `.gitignore` | S2 | inline | done |
| T3 | README, ADRs, docs copy | S12 | inline | done |
| T4 | Catalog build file and application config | S1, S3 | inline | done |
| T5 | Domain: Venue, Event, TicketType, status rules | S3, S5, S8 | inline | done |
| T6 | Flyway migration V1 | S3 | inline | done |
| T7 | Application service and repositories | S4-S8 | inline | done |
| T8 | REST API, DTOs, mapper, problem+json handler, trace filter | S4-S9 | inline | done |
| T9 | Domain unit tests | S8, S10 | inline | done |
| T10 | Integration tests with Testcontainers | S3-S11 | inline | done |
| T11 | Build, run tests, fix failures | S1, S10 | inline | done |

## Log

- **L1 (user request, verbatim):**
  > Actúa como tech lead y senior engineer de EventFlow.
  > Lee [EVENTFLOW_HANDOFF.md] y docs/07-opencode.md. Inspecciona el
  > repositorio y las herramientas disponibles. Consulta docs/06-roadmap-calidad.md
  > y docs/01-arquitectura.md. Empieza por Fase 0 y prepara Fase 1 (Catalog).
  > Antes de elegir versiones/APIs, verifica Context7 y documentación oficial.
  > No implementes otras fases todavía. Propón el plan, implementa en pasos
  > pequeños, ejecuta build y tests, corrige fallos y actualiza documentación.
  > Al terminar, entrega: archivos modificados, decisiones, comandos ejecutados,
  > resultados reales de tests, limitaciones y siguiente fase. Detente.

- **L2 (environment):** working directory was empty and not a git repository; the
  specification lived only in the Obsidian vault. Docs were copied into `docs/` so the
  repository is self-contained.
- **L3 (tooling):** no Java and no Gradle on `PATH`. JDK 21 found at
  `~/.jdks/graalvm-ce-21.0.2`; Gradle 9.9.2 downloaded to `/tmp/opencode` only to generate
  the committed wrapper. Docker 29.1.3 and Compose v5.7.0 available.
- **L4 (version research):** Boot 4.2.0, springdoc 3.2.1, Testcontainers 2.0.5, Flyway
  12.11.0 confirmed from Maven Central metadata, Boot 4.2 docs and springdoc release notes.
  Boot 4 renames: `spring-boot-starter-webmvc`, `spring-boot-starter-flyway`; Testcontainers
  2.0 relocates `PostgreSQLContainer` to `org.testcontainers.postgresql` and drops its
  generic parameter; PostgreSQL 18 images expect the volume at `/var/lib/postgresql`.
- **L5 (out of scope, deliberate):** `outbox_event` table and `EventPublished` publishing are
  Phase 3. Creating an unused table now would be speculative; the publish state machine is
  the gate they will consume.
- **L6 (build fix):** `include("services:catalog")` created an intermediate `:services`
  project that inherited the Boot plugin and failed `bootJar` with no main class. Replaced
  with flat project names mapped to directories (`:catalog` → `services/catalog`), which
  also gives shorter task paths.
- **L7 (deprecation fix):** `HttpStatus.UNPROCESSABLE_ENTITY` is deprecated in Spring 7 in
  favour of `UNPROCESSABLE_CONTENT` (RFC 9110 renamed 422 to "Unprocessable Content"). The
  status code stays 422; the constant and the MockMvc assertion were updated.

## Verification evidence

- `./gradlew :catalog:compileJava` — BUILD SUCCESSFUL, no warnings with `-Xlint:deprecation`.
- `./gradlew :catalog:test` — 34 tests, 0 failures, 0 errors:
  - `EventDomainTest` 10 tests, `VenueDomainTest` 3 tests, `CatalogApiIntegrationTest` 21 tests.
  - The integration test starts `postgres:18-alpine` through Testcontainers, so Flyway and
    the JPA mappings are validated against real PostgreSQL on every run.
- `./gradlew build` — BUILD SUCCESSFUL (8 tasks).
- End-to-end smoke test with the built jar against Compose PostgreSQL 18.7:
  - `/actuator/health` → `{"status":"UP"}`; Flyway applied migration v1.
  - `POST /api/venues` → 201; `POST /api/events` → 201 `DRAFT` with 2 ticket types.
  - `GET /api/events` before publish → `totalElements: 0`.
  - `POST /api/events/{id}/publish` → `PUBLISHED`; listing → 1 element.
  - Second publish → 409; unknown event → 404 problem+json with `code` and `traceId`.
  - `/v3/api-docs` → OpenAPI 3.1.0 with the custom title; `/swagger-ui/index.html` → 200.
- Docker Compose container reported `healthy` before the service started.
