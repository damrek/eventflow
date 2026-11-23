# EventFlow — Phase 6 (Deployment: Compose and k3d)

- **Objective:** ship the runtime images, the Compose `app` profile and the k3d deployment so the
  whole system starts from a clean checkout with one documented command per environment. Gate from
  `docs/06-roadmap-calidad.md`: `Arranque documentado; probes y dos réplicas de Booking.`
- **Branch:** `feat/deployment` (from `main` after PR #5).
- **Delivery strategy:** `ask-on-risk` (forecast ~1,100 authored lines of images, manifests and
  scripts → work-unit commits; publishing stays a human decision).
- **Test runners:** Gradle (`./gradlew :catalog:test`), Testcontainers PostgreSQL/RabbitMQ/Redis;
  shell verification scripts against the running stack (`curl`, `jq` not required); `docker compose
  config` for Compose validation; `kubectl apply --dry-run=client` plus a real cluster for k3d.

## Specs

- **S1 — Service runtime images.** One multi-stage `docker/service.Dockerfile` parameterised by
  `ARG SERVICE` builds any of the five services: builder `eclipse-temurin:21-jdk-alpine` runs the
  Gradle wrapper (`:${SERVICE}:bootJar -x test`, BuildKit cache mount for `GRADLE_USER_HOME`),
  runtime `eclipse-temurin:21-jre-alpine` runs `java $JAVA_OPTS -jar /app/app.jar` as non-root uid
  `10001`. `curl` is installed in the runtime image only because Compose healthchecks need an HTTP
  client inside the container. Tags are pinned and were checked on Docker Hub on 2026-11-23:
  `eclipse-temurin:21-jdk-alpine`, `eclipse-temurin:21-jre-alpine`, `nginx:1.29-alpine`,
  `node:24-alpine` (the SPA needs Node 22+). A root `.dockerignore` keeps `build/`, `frontend/node_modules/`,
  `.git/` and the `odd/`+`docs/` trees out of the build context.
- **S2 — Front door image.** `frontend/Dockerfile` builds the SPA with `node:24-alpine`
  (`npm ci && npm run build`) and serves it with `nginx:1.29-alpine` under two targets:
  `runtime-proxy` (Compose) renders `frontend/nginx/default.conf.template` through the official
  `envsubst` entrypoint and proxies `/api/catalog|booking|notification` to `${*-UPSTREAM}` with the
  same prefix rewrite the Vite proxy performs, plus SPA fallback `try_files $uri /index.html`;
  `runtime-static` (k3d) serves only the SPA so the Ingress owns API routing and nginx has no
  upstream DNS to resolve at start.
- **S3 — Actuator probes.** Every service exposes `/actuator/health/liveness` and
  `/actuator/health/readiness`: `management.endpoint.health.probes.enabled=true` and the readiness
  group is `readinessState,db` where a database exists (`readinessState` only for the simulator).
  Redis and the broker stay out of readiness on purpose: Redis is a throttle that fails open
  (ADR 0011) and a broker restart must not drain every replica. `payment-simulator` gains
  `spring-boot-starter-actuator`. A catalog integration test asserts both probes answer `200` and
  that the readiness group reports the database indicator.
- **S4 — Compose `app` profile.** `compose.yaml` keeps `core` untouched and adds `catalog`,
  `booking`, `notification`, `payment`, `payment-simulator` and `frontend` under profile `app`,
  built from the images above, configured with in-network URLs
  (`jdbc:postgresql://postgres:5432/<db>_db`, `RABBITMQ_HOST=rabbitmq`, `REDIS_HOST=redis`,
  `PAYMENT_PROVIDER_URL=http://payment-simulator:8085/simulator/authorizations`), started after
  `depends_on: condition: service_healthy`, one healthcheck per service against
  `/actuator/health`, and `restart: unless-stopped`. Only the front door publishes a port
  (`${APP_PORT:-8080}`); the five services stay internal. `.env.example` documents `APP_PORT`,
  `EVENTFLOW_TAG` and the image tag conventions.
- **S5 — Compose operations.** `scripts/stack-up.sh` builds and starts profile `app` plus `core`,
  waits for every healthcheck and prints the URLs; `scripts/stack-verify.sh` proves the documented
  flow through the front door only (venue -> event -> publish -> waiting room -> hold -> checkout ->
  confirmed -> notification) and asserts `available + held + sold = capacity`;
  `scripts/stack-down.sh [--volumes]` stops the stack (and only with the flag deletes data).
  Builds run with `COMPOSE_PARALLEL_LIMIT=1` so the five Gradle builds do not contend for one cache
  mount.
- **S6 — k3d cluster and configuration.** `scripts/k3d-up.sh` creates cluster `eventflow`
  (1 server + 1 agent, Traefik enabled, 80/443 mapped), creates the namespace, the Secret from
  `.env` (`kubectl create secret generic eventflow-secrets`; no credential is committed) and the
  ConfigMap, builds and imports the six images, then `kubectl apply -k deploy/k8s`. Stateful
  dependencies run inside the cluster so the deployment is self-contained: PostgreSQL (StatefulSet,
  PVC, the same idempotent init script as a ConfigMap), RabbitMQ (StatefulSet, PVC) and Redis
  (Deployment, no PVC — ephemeral by ADR 0011). `scripts/k3d-down.sh [--delete-cluster]` stops it.
- **S7 — k3d workloads.** One Deployment + Service per API (Booking `replicas: 2`, the rest `1`),
  startup/readiness/liveness probes on the Actuator endpoints with `db` in readiness, CPU/memory
  requests and limits, non-root `securityContext`, `imagePullPolicy: IfNotPresent`, and
  `terminationGracePeriodSeconds` large enough for the listener to finish. Frontend
  (`runtime-static`) behind a single Ingress; three `ReplacePathRegex` Middlewares strip
  `/api/catalog`, `/api/booking` and `/api/notification` to `/api` so the browser keeps one origin.
- **S8 — Deployment verification and docs.** `scripts/k3d-verify.sh` proves the phase gate against
  the cluster: Booking is `2/2`, both replicas answer (port-forward to each pod), an admission token
  issued by one replica is accepted by the other, concurrent holds across both replicas never
  oversell, and a deleted Booking pod is replaced while the ingress keeps serving. `README.md`
  documents both startup paths, `docs/05-infra-observabilidad.md` records what is implemented,
  `docs/adr/0013-containerization-and-deployment.md` records the decisions (images, probes,
  in-cluster stateful services, single front door, pinned tags, Traefik middlewares).

## Tasks

- **T1 — Service images** (S1). Route: inline. Commit: `build(docker): add the service runtime image`.
- **T2 — Front door image** (S2). Route: inline. Commit: `build(frontend): serve the SPA through nginx`.
- **T3 — Actuator probes + test** (S3). Route: inline. Commit: `feat(actuator): expose liveness and readiness probes`.
- **T4 — Compose app profile** (S4). Route: inline. Commit: `feat(compose): run the whole stack from images`.
- **T5 — Compose operations and verification** (S5). Route: inline. Commit: `feat(scripts): add stack up, down and verify`.
- **T6 — k3d cluster, configuration and workloads** (S6, S7). Route: inline. Commit:
  `feat(deploy): add the k3d manifests and cluster scripts`.
- **T7 — k3d verification and docs** (S8). Route: inline with an independent verifier (Risk: item 5).
  Commit: `docs: record phase 6 deployment`.

## Log

- **L1 (2026-11-23, user, verbatim):** "let's implement next phase but before modify any file show me
  the proposed plan, please"
- **L2 (2026-11-23, user, choice on the scope block):** "Full Phase 6, staged (recommended)" —
  stage 6A (Compose) first, then stage 6B (k3d) including the install of `k3d` and `kubectl` as
  user binaries under `~/.local/bin` (no root).
- **L3 (2026-11-23, decision):** phase row read from `docs/06-roadmap-calidad.md`; gate is
  `Arranque documentado; probes y dos réplicas de Booking`. Booking is already multi-replica-safe by
  design (outbox and expiry claim with `FOR UPDATE SKIP LOCKED`, admission pop atomic in Lua), so the
  gate proves it instead of adding new coordination.
- **L4 (2026-11-23, evidence):** `docker 29.1.3`, `docker compose v5.7.0`, 30 GB RAM, 12 cores;
  `k3d`, `kubectl` and `helm` were absent before this phase. Image tags verified on Docker Hub.
