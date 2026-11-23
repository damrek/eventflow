# EventFlow — Phase 5 (Frontend and Redis waiting room)

- **Objective:** the Redis virtual waiting room in Booking (join, position, batch admission,
  admission tokens, distributed rate limiting) plus the React SPA that walks the full flow:
  catalog → waiting room → hold → payment → notification. Gate from `docs/06` and
  `docs/04-frontend-redis.md`: a user joins, sees the position, is admitted; an expired or invalid
  token cannot skip an active wait; the rate limit is shared by two Booking instances; a Redis
  restart loses the queue but never oversells.
- **Branch:** `feat/frontend-waiting-room` (from `main` after PR #4).
- **Delivery strategy:** `ask-on-risk` (forecast ~3,000 authored lines → work-unit commits).
- **Test runners:** Gradle (`./gradlew :booking:test`), JUnit 6, Testcontainers PostgreSQL,
  RabbitMQ and Redis; frontend `npm test` (Vitest + Testing Library) and `npm run build` (Vite).

## Specs

- **S1 — Redis in local infrastructure.** `compose.yaml` gains a `redis` service on profile `core`
  (`redis:8-alpine`, ephemeral: no volume, no AOF), host port `6379` (`REDIS_PORT`), healthcheck
  `redis-cli ping`. `.env.example` documents `REDIS_HOST`, `REDIS_PORT` and the waiting-room /
  rate-limit knobs. Booking adds `spring-boot-starter-data-redis` (Boot 4.2 BOM; starter name
  verified in the Boot 4 reference) with `spring.data.redis.host/port/connect-timeout/timeout`
  (2 s so a dead Redis fails fast). Booking's test base starts one Redis container per JVM on a
  pinned host port (same pattern as the pinned AMQP port) so an outage test can restart it.
- **S2 — Join and status.** `POST /api/waiting-room/{eventId}/join` and
  `GET /api/waiting-room/{eventId}/status`, both with `X-User-Id` (400 `USER_ID_REQUIRED` when
  missing, through `CurrentUserProvider`). Body:
  `{eventId, userId, status: "NOT_JOINED"|"WAITING"|"ADMITTED", position, waitingCount, token, expiresAt}`.
  `position` is 1-based (`ZRANK + 1`) and `null` unless `WAITING`; `token`/`expiresAt` only when
  `ADMITTED`. Join inserts the user once with a server-assigned monotonic score
  (`INCR waiting-seq:{eventId}`) in one Lua script, so a rejoin keeps the original position and no
  client clock is trusted. Status reads the token first, then the rank; a non-member answers
  `NOT_JOINED` with the current `waitingCount`. Keys: queue `waiting:{eventId}` (sorted set),
  sequence `waiting-seq:{eventId}`, token `waiting-token:{eventId}:{userId}` (string, TTL). If
  Redis is unreachable join/status answer `503 WAITING_ROOM_UNAVAILABLE`.
- **S3 — Batch admission.** `WaitingRoomDispatcher` runs on
  `booking.waiting-room.dispatch-interval` (default `PT2S`) and, for every event in the
  `waiting-events` registry, admits up to `batch-size` (default `25`) members when the event's
  PostgreSQL inventory still has `available > 0` (inventory unreadable → skip the event). One Lua
  script pops the members, writes each admission token with
  `booking.waiting-room.admission-ttl` (default `PT2M`), and deletes the queue key when it becomes
  empty plus its registry entry; the sequence key outlives the queue so ordering stays monotonic.
  A member is admitted once; a token that expires is not renewed and the user must rejoin at the
  tail. Public `dispatch()` returns admitted members per event so tests can drive it without
  sleeping. The registry exists so the dispatcher never calls `KEYS`/`SCAN`.
- **S4 — Admission enforcement on reservation creation.** `POST /api/reservations` accepts
  `X-Admission-Token`. While the event's queue is active (`EXISTS waiting:{eventId}`), a missing,
  expired or foreign token answers `403 NOT_ADMITTED`; a token stored for that user and event is
  the only accepted proof and does not replace the inventory transaction. Without an active queue
  the gate is off, and if Redis is unreachable the gate fails open (warn log) because PostgreSQL
  remains the authority and Redis is ephemeral. `Idempotency-Key` behaviour is unchanged.
- **S5 — Distributed fixed-window rate limit.** One Lua script per subject and endpoint
  (`INCR` + `PEXPIRE` in a single atomic call) under `rate:{endpoint}:{userId}`, applied to
  `waiting-room-join` (`booking.rate-limit.join-max`, default `60` per window) and
  `reservation-create` (`booking.rate-limit.reservation-max`, default `300`); window
  `booking.rate-limit.window` (default `PT1M`). Over the limit answers `429` with a `Retry-After`
  header (seconds) and problem code `RATE_LIMITED`. The state lives only in Redis, so two
  replicas share it; when Redis is unreachable the limiter fails open and logs a warning.
- **S6 — Redis gate tests.** With real containers: join/position/rejoin keeps the position;
  the dispatcher admits a batch and issues a token with expiry; the queue key disappears when
  drained; a reservation with an admitted token succeeds while the queue is active; a missing,
  expired or other user's token answers `403 NOT_ADMITTED`; with no queue the gate is off;
  `cleanup` keeps Redis between tests deterministic. A rate-limit test over HTTP proves the
  `429` + `Retry-After`; a second test proves the fixed window is shared by two independent
  Booking application instances over the same Redis ("two replicas"). A Redis outage test stops
  and restarts the pinned container and proves: join/status answer `503` while it is down, a
  reservation still succeeds and never oversells (gate and limiter fail open), and the waiting
  room works again after the restart with no queue state.
- **S7 — Frontend scaffold.** `frontend/` npm project with exact versions in `package.json` and a
  committed `package-lock.json`: React `19.3.1`, React DOM `19.3.1`, React Router `7.18.6`,
  TanStack Query `5.108.1`, React Hook Form `7.92.0`, Zod `4.6.7`, `@hookform/resolvers` `7.0.7`,
  Vite `8.3.8`, `@vitejs/plugin-react` `6.3.0`, TypeScript `5.9.3`, Tailwind CSS `4.3.5`,
  `@tailwindcss/vite` `4.3.5`, Vitest `5.1.1`, jsdom `31.0.1`, Testing Library
  (`react` `16.3.3`, `dom` `10.4.2`, `user-event` `14.6.13`, `jest-dom` `8.0.0`) and the
  matching `@types/*`. Versions respect the machine's npm `min-release-age` policy (all older than
  the 3-day guard). Layout: `src/api/`, `src/features/`, `src/components/`, `src/hooks/`,
  `src/routes/`. Scripts: `dev`, `build` (`tsc -b && vite build`), `preview`, `test`.
  Vite `server.proxy` and `preview.proxy` map `/api/catalog` → `:8081`, `/api/booking` → `:8082`,
  `/api/notification` → `:8083`, rewriting the service prefix to `/api`; the SPA always calls its
  own origin. TypeScript strict with project references; Tailwind 4 through the Vite plugin and
  `@import "tailwindcss";` (no PostCSS config, no `tailwind.config.js`).
- **S8 — Identity and API client.** One demo user id persisted in `localStorage`
  (`eventflow.userId`, generated once as `web-<uuid>`), sent as `X-User-Id`; the UI labels it as a
  demo identity, never as authentication. Typed `api/` modules for Catalog, Booking and
  Notification parse RFC 9457 problems into an `ApiError {status, code, detail, traceId}`, send
  `Idempotency-Key` on reservation creation and `X-Admission-Token` when provided, and expose
  loading/error states. No business rule is duplicated in the client.
- **S9 — Catalog and selection screens.** `/` lists published events (loading, empty, error,
  pagination) and `/events/:eventId` shows the detail plus live availability from Booking
  (capacity/available/held/sold, price) and a React Hook Form + Zod selection with at least one
  ticket type and quantity `1..10`; the selected items travel to the waiting room in repeated
  `item=<ticketTypeId>:<quantity>` query parameters. The form refuses quantities above
  `available` for that type.
- **S10 — Waiting room, hold and payment screens.** `/events/:eventId/waiting-room` joins on
  mount, polls the status every 2 s and shows the position and waiting count; on `ADMITTED` it
  navigates to `/events/:eventId/reserve?item=…`. That page reads the status again to recover the
  token (a reload must not lose the admission), redirects back when the user is not admitted, and
  creates the hold once with a fresh `Idempotency-Key`; it shows the reservation summary, the
  total and a live countdown to `expiresAt`. `POST /checkout` starts the payment and the page
  polls the reservation until `CONFIRMED`, `PAYMENT_FAILED` or `EXPIRED`, rendering the real
  backend state (never a simulated confirmation), with cancel while `HELD` and a retry that
  mints a new idempotency key after a terminal state. A `503` from the waiting room offers a
  direct path to the reserve page (the backend gate fails open).
- **S11 — Reservations and notifications screens.** `/reservations` lists the reservation ids
  kept in `localStorage` (`eventflow.reservations`) with their live backend status, and the
  notification mailbox (`GET /api/notifications?userId=…`) renders the user's messages with
  loading/empty/error states. `/reservations/:reservationId` is the status page from S10.
- **S12 — Frontend tests and build.** Vitest + Testing Library, jsdom, `@testing-library/jest-dom`
  setup: selection validation (quantity/limits), waiting-room states (waiting → admitted →
  navigation, and the 503 fallback), countdown rendering, reservation page (pay → polling →
  confirmed, failed and expired states), and one critical-flow test that renders the real routes
  with a fake `fetch` backend covering catalog → detail → selection → join → reserve → pay →
  confirmed. `npm test` and `npm run build` must pass.
- **S13 — Documentation.** README (Phase 5 status, Redis + frontend start commands, endpoints,
  proxy), `AGENTS.md` (frontend module, waiting-room slice, commands, invariants), `.env.example`,
  `docs/03-apis-eventos.md` (waiting-room endpoints, `X-Admission-Token`, `429`), `docs/04`
  as-built notes, the README roadmap status, ADR 0011 (waiting room, admission tokens, rate
  limiting, fail-open and ephemeral Redis) and ADR 0012 (frontend stack and proxy), the feature
  document.

Out of scope: catalog caching in Redis (not in the Phase 5 acceptance criteria), real
authentication, payment UI beyond the reservation status, admin screens, Docker images and k3d
(Phase 6), metrics/tracing (Phase 7), ESLint/CI wiring (Phase 8).

## API contract (as built by this phase)

| Method | Path | Headers | Notes |
| ------ | ---- | ------- | ----- |
| POST | `/api/waiting-room/{eventId}/join` | `X-User-Id` | Inserts once, returns position or token |
| GET | `/api/waiting-room/{eventId}/status` | `X-User-Id` | Poll; no side effects |
| POST | `/api/reservations` | `X-User-Id`, `Idempotency-Key`, `X-Admission-Token` | Token required only while the queue exists |

Errors: `403 NOT_ADMITTED`, `429 RATE_LIMITED` (+ `Retry-After`), `503 WAITING_ROOM_UNAVAILABLE`,
all RFC 9457 with `code` and `traceId`.

## Tasks

| ID | Task | Specs | Route | Status |
| -- | ---- | ----- | ----- | ------ |
| T1 | Redis infra, dependency, properties, test container | S1 | inline | done |
| T2 | Waiting-room slice: join/status, scripts, dispatcher | S2, S3 | inline | done |
| T3 | Admission gate and rate limit on reservation creation | S4, S5 | inline | done |
| T4 | Backend gate tests (waiting room, tokens, rate limit, outage) | S6 | inline | done |
| T5 | Frontend scaffold, proxy, config, identity, API client | S7, S8 | delegated (writer) | done |
| T6 | Catalog and selection screens | S9 | delegated (writer) | done |
| T7 | Waiting room, hold, payment, reservations, notifications | S10, S11 | delegated (writer) | done |
| T8 | Frontend tests and production build | S12 | delegated (writer) | done |
| T9 | README, AGENTS, env, docs, ADRs, roadmap | S13 | inline | done |

## Log

- **L1 (user request, verbatim):** `lets implement next phase: Phase 5: Frontend + Redis waiting room`
- **L2 (phase identified):** `docs/06-roadmap-calidad.md` Phase 5 is the React SPA with gate
  "user walks catalog → wait → reservation → payment → notification"; `docs/04-frontend-redis.md`
  adds the Redis waiting room, admission tokens and distributed rate limiting. Phases 0-4 are
  merged on `main` (PR #4, merge commit `445563e`).
- **L3 (versions and stack verified):** the frontend matrix was installed and built in a scratch
  project under `/tmp/opencode` (Vite 8 + plugin-react 6 + Tailwind 4 + Vitest 5 + RTL) and the
  machine's npm `min-release-age=3` policy was respected (React 19.3.1, React Router 7.18.6,
  TanStack Query 5.108.1, `@hookform/resolvers` 7.0.7 were the newest releases outside the guard).
  Zod 4 coercion breaks the React Hook Form resolver types, so forms use `z.number()` with
  `register(..., { valueAsNumber: true })`. Boot 4.2 exposes `spring-boot-starter-data-redis` and
  `spring.data.redis.*` (Boot 4 reference), and `redis:8-alpine` exists on Docker Hub.
- **L4 (decisions):** the admission token is enforced only while the event's queue exists, and the
  gate and limiter fail open when Redis is unreachable: Redis is a throttle, PostgreSQL decides
  stock, and the documented invariant is that a Redis restart loses the queue but never duplicates
  or oversells inventory. Tokens are per user+event with TTL and are not consumed by a reservation,
  so an idempotent retry stays valid; a rejoin while waiting keeps the original position, and after
  a token expires the user re-enters at the tail. The SPA uses the same origin through a Vite proxy
  (dev and preview) instead of CORS, and the frontend keeps only reservation ids in
  `localStorage`, never server state.
- **L5 (implementation):** added the `redis` compose service (ephemeral, pinned host port) and
  `spring-boot-starter-data-redis`; Booking gained the `waitingroom/` slice (join/status Lua
  scripts, `waiting-events` registry, batch dispatcher driven by PostgreSQL availability, per
  user+event admission tokens), the `shared/web/rate/` fixed-window limiter (Lua `INCR`+`PEXPIRE`),
  `403 NOT_ADMITTED` / `429 RATE_LIMITED` + `Retry-After` / `503 WAITING_ROOM_UNAVAILABLE` through
  the RFC 9457 handler, and the admission gate plus rate limit on reservation creation. The
  frontend (T5-T8) was delegated to a writer with `frontend/` as its only edit surface; the parent
  owns the backend, docs and git.
- **L6 (backend evidence):** `./gradlew test --rerun-tasks` → BUILD SUCCESSFUL, 122 tests
  (catalog 36, booking 64, notification 6, payment 12, payment-simulator 4). New gate tests:
  `WaitingRoomIntegrationTest` (position and rejoin, batch admission and token expiry, `403` for
  missing/forged/foreign/expired tokens while the queue exists, gate off without a queue),
  `WaitingRoomRateLimitTest` (`429` + `Retry-After` on join and reservation creation),
  `RateLimitSharedStateTest` (a second real Booking application context shares the Redis window
  across two ports), `WaitingRoomRedisOutageTest` (`REDIS.stop()` → `503`, reservation still
  `201`, `available + held + sold = capacity`; `REDIS.start()` → room works and the old position is
  gone).
- **L7 (frontend implementation, delegated writer):** `frontend/` is an npm project with the exact
  pinned matrix, Vite + React + TypeScript + Tailwind, one shared proxy for `dev` and `preview`,
  typed API modules with RFC 9457 parsing, the demo identity and reservation ids in
  `localStorage`, and the six screens of S9-S11. A real defect was caught by the delegated RED
  run: the reserve page used to create the hold as soon as the status query resolved, so a
  `WAITING` user got a hold; the gate is now `ADMITTED` (or a 503 fail-open) and the regression is
  pinned. `item=<ticketTypeId>:<quantity>` is built without `URLSearchParams` so the colon survives;
  `location.search` is forwarded verbatim between the waiting room and the reserve page.
- **L8 (frontend evidence):** `cd frontend && npm test` → 6 files / 22 tests passed; `npm run build`
  → `tsc -b` clean, 199 modules, `dist/assets/index-*.js` 432.16 kB (gzip 133.81 kB), CSS 17.06 kB.
  The critical-flow test renders the real routes with a fake `fetch` backend and walks catalog →
  detail → selection → join → reserve → pay → confirmed, asserting the `X-Admission-Token`, the
  `X-User-Id` headers and that the confirmation came from a backend `GET`.
- **L9 (delivery):** work-unit commits on `feat/frontend-waiting-room`: `dded278` (Booking waiting
  room, gate, rate limit and tests with the Redis compose service) and `627c2bb` (the SPA and its
  tests). Push, PR and merge remain human decisions. The change names high-risk items 3 (a new
  rejection header/status on reservation creation) and 5 (new Redis dependency and infrastructure),
  so it gets one independent verifier before delivery.
- **L10 (independent verification, no blockers):** `gentle-ai-verify` reviewed `445563e..627c2bb`
  plus the docs and returned S1-S13 met. Advisories addressed in one correction batch:
  `WaitingRoomAdmissionGateTest` added (missing `X-User-Id` → 400, sold-out event admits nobody
  until a unit returns, unprovisioned event skipped, an admitted token still gets `422` when the
  units are not there, a real `PT1S` Redis expiry sends the user to the tail, idempotent replay
  keeps one reservation); the outage test now also proves a five-unit request against four
  available units answers `422 INSUFFICIENT_TICKETS`; the limiter fails open (Retry-After 1) if
  Redis dies between `INCR` and the TTL read; `Retry-After` is asserted as a positive integer; the
  SPA replays a failed hold creation with the same idempotency key (a lost response cannot
  double-hold) with a pinned test; the stale `SCAN` sentence in S3 and the 300-limit rationale in
  ADR 0011 were corrected.
- **L11 (final evidence):** `./gradlew test --rerun-tasks` → BUILD SUCCESSFUL, 128 tests
  (catalog 36, booking 70, notification 6, payment 12, payment-simulator 4), 0 failures; frontend
  `npm ci`, `npm test` (22) and `npm run build` green. Correction commits after the feature commits:
  `973ba17` (limiter fail-open on the TTL read), `e5f09ea` (new gate test and stronger outage
  proof), `5aea4cf` (same-key hold replay in the SPA); docs in their own commit. Phase 5 gate met;
  push/PR/merge wait for the next instruction.
