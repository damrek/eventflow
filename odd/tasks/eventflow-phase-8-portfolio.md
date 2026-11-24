# EventFlow — Phase 8 (Portfolio: k6, CI, ADRs, README and demo)

- **Objective:** close the roadmap's last row. Gate from `docs/06-roadmap-calidad.md`:
  `Pipeline verde y resultados de carga reproducibles`. Concretely: a CI pipeline that runs the
  existing gates on every PR, frontend lint/format that did not exist before, k6 scenarios whose
  numbers are recorded with the hardware they came from, the missing decision record (ADR 0008),
  and a README that states the problem and shows the system instead of only documenting commands.
- **Branch:** `feat/portfolio` (from `main` at merge commit `1191c25`, PR #7).
- **Delivery strategy:** `ask-on-risk` (forecast ~1,400 authored changed lines across workflow,
  lint config, k6 scripts, performance notes, ADR and README → work-unit commits; push, PR and
  merge stay human decisions). Scope decided in L2: ESLint + Prettier for the frontend only, and
  real screenshots captured from the running stack.
- **Test runners:** `cd frontend && npm run format:check && npm run lint && npm test && npm run build`;
  `./gradlew build` (Testcontainers, unchanged); `docker run --rm grafana/k6:2.4.0` for the load
  scenarios; `scripts/demo.sh` and `scripts/load-test.sh` against the running Compose stack.

## Specs

- **S1 — Frontend lint and format, without new runtime behaviour.** `frontend/` gains ESLint 9 (flat
  config) and Prettier, pinned exactly in `package.json` and `package-lock.json`:
  `eslint@9.39.5`, `@eslint/js@9.39.5`, `typescript-eslint@8.73.0`, `eslint-plugin-react-hooks@7.1.1`,
  `eslint-config-prettier@10.1.8`, `prettier@3.10.1`, `globals@17.14.0`. Versions are those npm
  reports as published before 2026-11-21, because `~/.npmrc` enforces `min-release-age=3` and
  `ignore-scripts=true`; the pins are exact, never a range (ADR 0012).
  - Scripts: `lint` (`eslint .`), `format:check` (`prettier --check .`), `format`
    (`prettier --write .`). `npm test` and `npm run build` keep their behaviour.
  - Config: `eslint.config.js` with `js.configs.recommended` + `typescript-eslint` recommended
    (non type-checked), `react-hooks` recommended, and `eslint-config-prettier` last so Prettier
    owns formatting; browser globals for `src/`, node globals for the config files; ignore
    `dist/`, `node_modules/`, `package-lock.json`.
  - The whole frontend passes with **zero** reported problems and zero reformat-driven behaviour
    change: the Prettier pass touches only whitespace/quoting, and Vitest + `tsc -b` + the Vite
    build stay green. No backend file changes (no Spotless), so the Java diff of this phase is
    untouched by formatting.
  - Test: the three scripts exit `0` on the branch; a deliberately broken file (a `useState` call
    inside a condition, an unused import) is rejected by `npm run lint` during development, and
    removing it is what makes the check pass. Evidence: `npm run lint`, `npm run format:check`,
    `npm test`, `npm run build` outputs recorded in the Log.
- **S2 — CI pipeline on GitHub Actions.** `.github/workflows/ci.yml` runs on `push` to `main` and on
  every `pull_request`, with `concurrency` cancelling superseded runs, and three jobs:
  - `frontend` — `actions/setup-node` with Node 22 and npm cache, `npm ci`, then
    `npm run format:check`, `npm run lint`, `npm test`, `npm run build`.
  - `backend` — `actions/setup-java` (temurin 21) + `gradle/actions/setup-gradle` for the wrapper
    cache, then `./gradlew build` with **the whole suite** (Testcontainers runs on the runner's
    Docker; no test is skipped, no profile disables integration tests). `--stacktrace` on failure,
    and the test reports uploaded as an artifact.
  - `images` — builds the six container images (`catalog`, `booking`, `notification`, `payment`,
    `payment-simulator` via `docker/service.Dockerfile --build-arg SERVICE=`, and `frontend`) with
    `docker/build-push-action` and no push, so a Dockerfile regression fails the PR.
  - Actions are pinned to exact released versions (`actions/checkout@v7.0.1`,
    `actions/setup-node@v7.1.0`, `actions/setup-java@v6.3.0`, `gradle/actions/setup-gradle@v6.4.1`,
    `actions/upload-artifact@v7.0.2`, `docker/build-push-action@v7.4.0`), matching the versions the
    GitHub API reported on 2026-11-24; no `latest` tag anywhere.
  - k6 is **not** in the PR pipeline (the roadmap forbids heavy load tests per PR). Permissions are
    least-privilege (`contents: read`), and there is no secret in the workflow.
  - Test: `actionlint` is not installed, so the honest check is: every command the workflow runs is
    executed locally with the same JDK/Node majors and passes (Log evidence), the YAML parses
    (`python3 -c 'import yaml,sys; yaml.safe_load(open(...))'`), and a static
    `scripts/ci-invariants-test.sh` asserts the pins, the jobs, the absence of `npm install` and of
    load tests. Seeing the run actually green requires a push, which is a human decision (L3).
- **S3 — k6 scenarios and reproducible results.** `docs/performance/k6/` holds two scenarios that
  share one helper:
  - `read-path.js` — `constant-arrival-rate` (or ramping) against `GET /api/events?page=0&size=20`
    and `GET /api/events/{id}/availability`, mixed 80/20, thresholds `http_req_failed rate<0.01`
    and `http_req_duration p(95)<150`.
  - `reservation-contention.js` — many VUs create holds on an event with a known small capacity,
    each iteration with a unique `Idempotency-Key` and `X-User-Id`; `201` and `422
    INSUFFICIENT_TICKETS` are both expected answers, anything else is a failure; `setup()` seeds
    venue + event through Catalog and `teardown()` reads availability and asserts
    `available + held + sold == capacity`, `available >= 0`, `held === sold` bookkeeping and that the
    number of `201`s equals the units held.
  - `scripts/load-test.sh [scenario]` starts nothing implicitly: it requires the stack to be up
    (documented), runs the pinned `grafana/k6:2.4.0` image with `--network host`, passes the base URL
    and scenario options, and writes `docs/performance/results/<date>-<scenario>.json` plus the
    human summary. k6 v2 was checked: the JS API used (`http`, `check`, `options`, `thresholds`,
    `setup/teardown`) is unchanged.
  - Results are **real**: `docs/performance/README.md` and one dated results file record the hardware
    (`lscpu`, `free -h`, Docker version), the stack profile, the exact command, k6's summary and the
    thresholds' verdict. No projected or "typical" numbers, and a scenario that misses its threshold
    is reported as missed, not re-tuned into passing.
- **S4 — ADR 0008 and an index.** `docs/adr/0008-rabbitmq-over-kafka.md` records the decision the
  roadmap asks for and today only exists as a mention inside ADR 0009: RabbitMQ (AMQP 0.9.1) instead
  of Kafka, with the real trade-offs — per-message routing and per-queue DLQ/retry topology fit this
  system's small number of low-volume queues; publisher confirms + the transactional outbox give the
  durability that would otherwise argue for Kafka; Kafka's ordering/partitioning and replay log were
  not needed by any use case here; operational weight of a single broker vs a cluster. It states what
  would change the decision (throughput, replay, stream processing). The numbering gap (0007 → 0009)
  is closed and `docs/adr/README.md` lists every ADR with one line each.
- **S5 — README as a portfolio document.** `README.md` gains, above the operator material it already
  has: the problem and why the project exists, a feature list phrased as capabilities, an
  architecture section with an ASCII diagram of services/queues/databases plus the invariants that
  make it interesting (one conditional `UPDATE`, outbox/inbox, saga compensation, Redis as throttle
  only), a "how each claim is proven" table mapping invariant → test → command, a screenshot section
  (S6), a "what this does not do" limitations section, and links to `docs/adr`, `docs/performance`
  and the phase documents. The roadmap table moves Phase 8 to `done`. Everything stated is checkable
  in this repository: no coverage percentage, no user count, no performance number that S3 did not
  measure on this machine.
- **S6 — Scripted demo and real screenshots.** `scripts/demo.sh` walks the documented flow against
  the running stack with `curl` only (`jq` for ids), printing each step with what it proves: seed a
  venue, create and publish an event, watch Booking provision inventory, join the waiting room, hold
  tickets, checkout, wait for the payment result, read the notification mailbox. It exits non-zero
  the moment an expected state does not appear, so it is a smoke test and not a wall of output.
  `docs/assets/*.png` are screenshots of the real SPA and Grafana on this machine (catalog, waiting
  room, reservation with the paid status, the Grafana business dashboard, one Tempo trace), captured
  with the browser panel against `http://localhost:8080`, each captioned with what it shows and that
  it comes from the local Compose stack.
- **S7 — Close-out.** `docs/06-roadmap-calidad.md` keeps its plan text (it is the source spec) but the
  closing phase status lives in the README table and this document; `EVENTFLOW_HANDOFF.md` gains the
  reading order for the portfolio material; `.env.example` documents any new variable (none expected).
  One independent verifier reads this document and the branch and returns a verdict per `S#`.

## Tasks

- **T1 — ESLint + Prettier for the frontend, zero findings** (S1). Route: inline.
- **T2 — GitHub Actions CI** (S2). Route: inline.
- **T3 — k6 scenarios, runner, measured results** (S3). Route: inline.
- **T4 — ADR 0008 and the ADR index** (S4). Route: inline.
- **T5 — README as portfolio document** (S5). Route: inline.
- **T6 — `scripts/demo.sh` and screenshots** (S6). Route: inline.
- **T7 — Close-out and independent verification** (S7). Route: inline + one verifier.

## Log

- **L1 (2026-11-24, user, verbatim):** "let's implement next phase"
- **L2 (2026-11-24, user, choices on the scope block):** "Lint and format in CI: ESLint + Prettier,
  frontend only" and "Demo assets: Real screenshots + scripted walkthrough (Recommended)".
- **L3 (2026-11-24, decision):** the CI gate can only be *observed* green after a push, and push/PR
  are human decisions in this repository (`AGENTS.md`), so T2 stops at "every job command verified
  locally + the workflow is statically pinned and parseable" and says so plainly in the close-out.
- **L4 (2026-11-24, evidence):** repository state read before any write: `main` at `1191c25`, clean
  tree; no `.github/`, no `docs/performance/`, no `docs/adr/README.md`, no `0008`; `frontend/` has
  no formatter or linter config and its `package.json` scripts are `dev`, `build`, `preview`, `test`
  only. `k6` is not installed on this machine (the pinned Docker image is the runner).
- **L5 (2026-11-24, evidence, versions verified not assumed):** npm registry on 2026-11-24 reports
  `eslint@9.39.5` (2026-07-10), `@eslint/js@9.39.5` (2026-07-10),
  `typescript-eslint@8.73.0` (2026-11-16 — `8.74.0` was published 2026-11-23 and is refused by
  `min-release-age=3`), `eslint-plugin-react-hooks@7.1.1`, `eslint-config-prettier@10.1.8`,
  `prettier@3.10.1`, `globals@17.14.0`. `typescript-eslint` peers:
  `eslint ^8.57 || ^9 || ^10`, `typescript >=4.8.4 <6.1.0` — the project's `typescript@5.9.3` fits.
- **L6 (2026-11-24, evidence, actions and k6):** GitHub API `releases/latest` on 2026-11-24 reported
  `actions/checkout@v7.0.1`, `actions/setup-node@v7.1.0`, `actions/setup-java@v6.3.0`,
  `gradle/actions@v6.4.1`, `actions/upload-artifact@v7.0.2`, `docker/build-push-action@v7.4.0`.
  Docker Hub lists `grafana/k6` tags up to `2.4.0`; the v2.0.0 release notes were read and remove only
  long-deprecated CLI commands, the `externally-controlled` executor and the
  `k6/experimental/redis` module — the JS API these scenarios use is unchanged.
