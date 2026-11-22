# AGENTS.md — EventFlow

Monorepo of Spring Boot services for a ticket-booking portfolio project. One Gradle project
per service, each owning its own database; only `catalog` exists so far. The product
specification is in `docs/` (Spanish) and the workflow rules for agents in
`docs/07-opencode.md`. `EVENTFLOW_HANDOFF.md` is the index and lists the invariants.

## Commands

```bash
docker compose --profile core up -d   # PostgreSQL 18 on localhost:5432 (required by bootRun)
./gradlew build                       # compile + all tests (Docker required)
./gradlew :catalog:test               # tests only
./gradlew :catalog:test --tests "com.eventflow.catalog.event.EventDomainTest"
./gradlew :catalog:bootRun            # Catalog API on localhost:8081
```

- There is no system Gradle: always use `./gradlew` (wrapper 9.9.2).
- This machine has no `java` on `PATH`. Export `JAVA_HOME` to a JDK 21 before running Gradle;
  the local one is `~/.jdks/graalvm-ce-21.0.2`.
- The Gradle project is `:catalog`, mapped to `services/catalog` in `settings.gradle.kts`.
  Do not "normalize" it to `:services:catalog`: that creates an intermediate `:services`
  project which inherits the Boot plugin and fails `bootJar` with no main class.

## Tests

- Integration tests start `postgres:18-alpine` through Testcontainers, so **Docker is a hard
  prerequisite**; there is no skip switch and no H2 anywhere (ADR 0003).
- `@Sql` truncates `ticket_type, event, venue` before every test
  (`src/test/resources/db/cleanup.sql`). Add new tables there when a service gains them.
- `spring.jpa.hibernate.ddl-auto=validate` runs on every context load: a test failing with a
  schema-validation error means the entity and the migrations disagree.

## Layout and ownership

- Package-by-feature inside each service: `event/`, `venue/`, `shared/`, `config/`
  (ADR 0005). Each slice owns entity, value objects, repository, service, controller and
  `dto/`. Do not reintroduce `domain/`, `application/` or `api/` layers.
- `TicketType` belongs to `event/`: `Event.draft` enforces capacity, price and unique-name
  invariants across its ticket types. Splitting it out breaks the aggregate.
- Business rules live in aggregates (`Event.draft`, `Event.publish`), never in controllers,
  services or listeners. `shared/` holds cross-slice primitives only — no business logic.
- `EventService` may use `VenueRepository` to reference the venue aggregate; it must not call
  `VenueService`.
- Constructor injection only: `final` fields plus `@RequiredArgsConstructor`. No field
  `@Autowired`.
- `GET /api/events` returns only `PUBLISHED` events; publishing is `DRAFT -> PUBLISHED` and a
  second call is `409`. Errors are RFC 9457 with a stable `code` and `traceId`
  (`shared/web/ApiExceptionHandler`); route new failures through it.
- Mapping is explicit in `Response.from(entity)` factories. Never serialize entities or
  Spring's `Page` directly.

## Database

- Flyway is the only schema source. Add `V<n>__description.sql`; never edit an applied
  migration. One PostgreSQL instance, one database per service (`catalog_db`); never query
  another service's database.

## Toolchain quirks (verified against Boot 4.2.0)

- Boot 4 modular starters: `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-flyway`.
- JSON databind is Jackson 3 (`tools.jackson.*`); annotations remain
  `com.fasterxml.jackson.annotation`.
- Versions come from the Boot BOM. `compileOnly` and `annotationProcessor` do **not** inherit
  `implementation(platform(...))`: add the platform to each configuration that needs it.
- springdoc 3.2.1 targets the Boot 4.2 line; upgrading Boot requires a matching springdoc.
- PostgreSQL 18 images expect the volume at `/var/lib/postgresql`, not `.../data`.
- Lombok: getters, JPA/injection constructors and loggers only. No `@Data`, `@Setter` or
  `@Builder` on entities and no Lombok on records (ADR 0004).
- Before adding a dependency or using a framework API, verify version/API against Context7 or
  official docs; never invent versions (`docs/07-opencode.md`, rules 4-5).

## Conventions

- Conventional Commits, no AI attribution or co-author trailers.
- `main` holds the bootstrap; feature work goes on `feat/<name>`. Push, PR and merge are human
  decisions.
- Phase tracking lives in `odd/tasks/*.md` (specs `S#`, tasks, log); architecture decisions in
  `docs/adr/`. Update both when behavior changes.
