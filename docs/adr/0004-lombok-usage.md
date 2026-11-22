# ADR 0004 — Lombok usage policy

- **Status:** accepted
- **Date:** 2026-11-22
- **Deciders:** tech lead

## Context

Entities, application services and controllers repeat mechanical code: getters, no-argument
constructors for JPA, constructor injection and loggers. The project requested Lombok to
remove that boilerplate. Lombok is also easy to misuse: `@Data` on a JPA entity generates
`equals`/`hashCode` over mutable state, and `@Builder` on an entity bypasses the factory that
enforces invariants.

## Verification

- Spring Boot 4.2.0 dependency coordinates manage `org.projectlombok:lombok` **1.18.48**,
  which is also the latest release on Maven Central.
- Spring Boot documentation states that with Gradle the processor must be declared in the
  `annotationProcessor` configuration (and ordered before `spring-boot-configuration-processor`).
- The Boot BOM constrains `implementation` only; `compileOnly` and `annotationProcessor` are
  separate configurations and need the platform declared explicitly.

## Decision

1. **Build-time only.** Lombok is declared as `compileOnly` + `annotationProcessor`, never
   `implementation`. Verified: it is absent from `runtimeClasspath` and from `bootJar`.
2. **Allowed usages:**
   - `@Getter` on JPA entities, replacing hand-written getters.
   - `@NoArgsConstructor(access = PROTECTED)` for Hibernate.
   - `@RequiredArgsConstructor` on Spring components with `final` dependencies.
   - `@Slf4j` for loggers.
3. **Forbidden usages:**
   - `@Data`, `@Setter`, `@EqualsAndHashCode`, `@ToString` on entities: state changes only
     through domain methods, and identity is the `id`.
   - `@Builder` on entities: creation must go through `Venue.create` / `Event.draft` /
     `TicketType.create`, which enforce the capacity, price and uniqueness invariants.
   - Lombok on records (`api/dto`, commands): records already remove the boilerplate and
     their validation annotations must stay visible at the declaration.
4. **Explicit methods stay explicit.** `Event.getTicketTypes()` keeps returning an
   unmodifiable view; Lombok does not generate a method that already exists, so the guard
   survives the refactor.
5. **Public API unchanged.** The compiled signatures after the refactor match the previous
   hand-written getters; the 34 tests and the bytecode inspection confirm it.

## Consequences

- Annotation processing is required in builds and IDEs; the processor is managed by the Boot
  BOM, so it upgrades with Spring Boot.
- Generated members are invisible in source. This ADR is the boundary: readers can assume a
  generated getter, and nothing else.
- Adding a Lombok annotation outside the allowed list is a review finding, not a style
  preference.

## Alternatives considered

- **No Lombok, keep hand-written code:** zero build surface, but the boilerplate grows with
  every service; rejected after the explicit request.
- **Lombok everywhere including entities and DTOs:** rejected, it would hide invariants and
  duplicate what records already solve.
- **Kotlin/records for entities:** records are immutable and cannot model a JPA aggregate
  with mutable state.
