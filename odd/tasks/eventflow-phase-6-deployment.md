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
  `/actuator/health/readiness`. Boot 4.2 already enables both groups
  (`management.endpoint.health.probes.enabled` defaults to `true`), so the change is the readiness
  membership: `readinessState,db` where a database exists (the simulator keeps the default). Redis
  and the broker stay out of readiness on purpose: Redis is a throttle that fails open (ADR 0011)
  and a broker restart must not drain every replica. `payment-simulator` gains
  `spring-boot-starter-actuator`. `ActuatorProbeTest` asserts both probe paths answer `200`, that
  readiness is a member of `db` while liveness is not, and that the aggregate health is `UP`.
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
  (1 server + 1 agent, Traefik enabled, only port 80 mapped from the load balancer — TLS is out of
  scope), creates the namespace, the Secret from `.env` (`kubectl create secret generic
  eventflow-secrets`; no credential is committed) and the ConfigMap, builds and imports the six
  images, then `kubectl apply -k deploy/k8s`. Stateful
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

- **T1 — Service images** (S1). Route: inline. Done: `7c62cd4`.
- **T2 — Front door image** (S2). Route: inline. Done: `db481fd`.
- **T3 — Actuator probes + test** (S3). Route: inline. Done: `35a148d`.
- **T4 — Compose app profile** (S4). Route: inline. Done: `31cf160`.
- **T5 — Compose operations and verification** (S5). Route: inline. Done: `380ba1f`.
- **T6 — k3d cluster, configuration and workloads** (S6, S7). Route: inline. Done: cluster,
  manifests and scripts verified by `k3d-verify.sh` (commit after the docs).
- **T7 — k3d verification and docs** (S8). Route: inline with an independent verifier (Risk: item 5).
  Done: verifier verdict `met` on S1–S8 with no change-caused blocker; four advisories actioned (L13).

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
- **L5 (2026-11-23, discovery):** `management.endpoint.health.probes.enabled` defaults to `true` in
  Boot 4.2 (checked in `spring-configuration-metadata.json` of `spring-boot-health:4.2.0`, after the
  probe endpoints answered without the property), so S3 shrank to the readiness group membership.
  RED was observed by stashing only `services/catalog/.../application.yml`:
  `readinessCoversTheDatabaseAndLivenessDoesNot` failed on `isMember("db")`; GREEN after restoring it.
  `:catalog:test` runs 39 tests, 0 failures; `:payment-simulator:test` passes.
- **L6 (2026-11-23, evidence stage 6A):** `./scripts/stack-up.sh` built and started the six images
  and every healthcheck reported healthy. `./scripts/stack-verify.sh` passed the whole documented
  flow through `http://localhost:8080` exactly as a browser would: venue, event, publish, provision,
  waiting room admission, hold, checkout, `CONFIRMED`, `BOOKING_CONFIRMED` in the mailbox and
  `available=9, held=0, sold=1, capacity=10`.
- **L7 (2026-11-23, tooling):** installed `kubectl v1.37.2` and `k3d v5.9.0` (k3s `v1.35.5`) as user
  binaries in `~/.local/bin` from the official release URLs; no root and no system package change.
- **L8 (2026-11-23, decision):** the frontend serves two targets because nginx cannot bind `:80` as
  a non-root user without a capability: Compose keeps `nginx:1.29-alpine` on `:80` behind the
  published port, while k3d uses `nginxinc/nginx-unprivileged:1.29-alpine` on `:8080` with all
  capabilities dropped.
- **L9 (2026-11-23, defect found and fixed):** every service pod crashed at start with
  `Invalid value '${CATALOG_PORT:8081}' for configuration property 'server.port'`. Cause:
  Kubernetes service links inject `<SERVICE>_PORT=tcp://<ip>:<port>` for every Service in the
  namespace, so `CATALOG_PORT`, `BOOKING_PORT`, `PAYMENT_PORT`, `NOTIFICATION_PORT` and
  `PAYMENT_SIMULATOR_PORT` were no longer integers. Fixed with `enableServiceLinks: false` on every
  pod spec, which leaves the explicit environment as the only source.
- **L10 (2026-11-23, defect found and fixed):** RabbitMQ crash-looped with
  `.erlang.cookie must be accessible by owner only`; a `fsGroup` on the pod made the volume
  group-owned. Removed `fsGroup` for that StatefulSet, which the image's own entrypoint handles
  (it drops from root to the `rabbitmq` user). Fresh PVC so the cookie is created cleanly.
- **L11 (2026-11-23, evidence stage 6B):** `./scripts/k3d-up.sh` created the cluster
  (1 server + 1 agent, ingress on `:8090`), imported the six images and applied
  `kubectl apply -k deploy/k8s`. `./scripts/k3d-verify.sh` then passed the whole gate: the
  documented flow through the Ingress (the same checks as Compose, on `:8090`),
  Booking `2/2`, both replicas reachable, a token minted through replica A honoured by replica B,
  `20` concurrent holds on `10` units across both replicas ending `10 held / 10
  INSUFFICIENT_TICKETS` with `available + held + sold = capacity`, a deleted Booking pod replaced
  while the Ingress kept serving, both replicas leaving readiness when PostgreSQL was scaled to
  zero without a liveness restart, and readiness returning after PostgreSQL came back.
- **L12 (2026-11-23, verification nuance):** the first readiness run attributed a restart to
  liveness. It was a replacement pod starting while PostgreSQL was down: the deployment recreated
  the pod that the pod-replacement step had just deleted, and its Flyway run could not connect.
  The script now waits for the deleted pod to disappear before the readiness step and compares
  restart counts only for pods that existed before the outage; a replacement pod is not evidence
  about liveness.
- **L13 (2026-11-23, independent verification):** `gentle-ai-verify` read the whole document and the
  diff `a3e29fb..1e43bba` and returned `met` for S1–S8 with no change-caused blocker, plus four
  advisories:
  1. S6 said "80/443 mapped" and only 80 is: the sentence now says port 80 and that TLS is out of
     scope.
  2. `k3d-up.sh` could not restart a stopped cluster (the documented `k3d-down.sh` then
     `k3d-up.sh` path): it now runs `k3d cluster start` when `serversRunning` is `0`, verified by
     stopping the cluster and running the script again ("Starting the stopped cluster eventflow",
     then Booking `2/2` and the ingress answering `200`).
  3. The ADR claimed a password rotation is an `.env` edit plus a re-run; PostgreSQL and RabbitMQ
     read credentials only when their volumes are initialised, so ADR 0013 §6 and the script comment
     now say the PVC must be deleted too.
  4. The Compose front door cached the upstream address at nginx start, so recreating one service
     could leave its prefix at `502`. The template now resolves the upstream per request through
     Docker's embedded DNS (`resolver 127.0.0.11`, `valid=10s`, `set $catalog …` + `rewrite`). The
     rewrite was re-verified (`/api/catalog/events` and `/api/booking/...` reach the services) and
     the whole flow passed again through `:8080`; the IP-change path itself could not be
     reproduced because Compose reassigns the same address on `--force-recreate`.
  The verifier's own probe ships as `scripts/deployment-invariants-test.sh` (67 checks over S1, S3,
  S6, S7, L9 and L10, green at HEAD and red when `enableServiceLinks`, `listen 8080` or the
  readiness `db` member is removed).
