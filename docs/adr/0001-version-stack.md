# ADR 0001 — Version stack and build baseline

- **Status:** accepted
- **Date:** 2026-11-22
- **Deciders:** tech lead

## Context

The specification fixes the target stack family (Java 21, Spring Boot 4.x, Gradle Wrapper,
PostgreSQL, Flyway, Testcontainers) but forbids inventing versions or APIs. Every version
and starter name had to be confirmed against primary sources before the build was written.

## Verification

| Item | Evidence |
| --- | --- |
| Spring Boot | Maven Central `spring-boot-gradle-plugin` metadata: `4.2.0` is the current release; Boot docs URL `/spring-boot/4.2/` resolves. |
| Gradle | `services.gradle.org/versions/current` reports `9.9.2`; wrapper generated from that distribution. |
| Starters | Boot 4.2 POMs confirmed on Maven Central: `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-boot-starter-webmvc-test`, `spring-boot-testcontainers`. |
| springdoc | Release `v3.2.1` (2026-11-20) states "Spring Boot 4.2.x line (built on Spring Boot 4.2.0)". Compatibility table: Boot 4.x → springdoc 3.x. |
| Testcontainers | Boot 4.2 dependency coordinates manage `2.0.5`; modules renamed to `testcontainers-postgresql` / `testcontainers-junit-jupiter`, and `PostgreSQLContainer` moved to `org.testcontainers.postgresql` and is no longer generic. |
| Flyway | Boot 4.2 manages `12.11.0`; PostgreSQL support ships as a separate `flyway-database-postgresql` artifact. |
| PostgreSQL image | `postgres:18-alpine` exists. Since PostgreSQL 18 the image expects the volume at `/var/lib/postgresql`, not `/var/lib/postgresql/data`. |

## Decision

1. **Java 21 toolchain**, Spring Boot **4.2.0**, Gradle **9.9.2** via wrapper, Kotlin DSL.
2. Boot 4 is modular: use `spring-boot-starter-webmvc` (not `spring-boot-starter-web`)
   and `spring-boot-starter-flyway` (which replaced the manual `flyway-core` setup).
3. Dependencies are versionless except springdoc, because the Boot BOM
   (`SpringBootPlugin.BOM_COORDINATES`) pins them. This is the documented Spring pattern
   and keeps managed versions consistent with the Boot release.
4. Build is a **monorepo with one Gradle project per service** under `services/`.
   Cross-service code sharing is forbidden by the architecture invariants, so no shared
   library module exists.
5. Test dependency packages for Boot 4 live in technology-specific modules:
   `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.

## Consequences

- Upgrading Boot also upgrades Flyway, Testcontainers and the PostgreSQL driver, and the
  integration tests immediately prove whether the upgrade is safe.
- `spring-boot-starter-web` still exists as a deprecated alias in 4.2; new code must not
  use it, to keep the Boot 4 module boundaries explicit.
- The Gradle wrapper is committed so a clone builds without a local Gradle installation.

## Alternatives considered

- **Ivy/Maven build:** discarded, the specification fixes Gradle Wrapper.
- **`io.spring.dependency-management` plugin:** unnecessary; `platform(...)` achieves the
  same managed versions with less plugin surface.
- **Shared `common` library:** rejected; it would create implicit coupling between
  services that the architecture explicitly forbids.
