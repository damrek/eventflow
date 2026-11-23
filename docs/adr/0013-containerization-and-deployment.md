# ADR 0013 — Container images, Compose app profile and k3d deployment

**Status:** Accepted (Phase 6)

## Context

Phase 6 is the deployment row of `docs/06-roadmap-calidad.md`: `Docker Compose y k3d`, with the
quality gate `Arranque documentado; probes y dos réplicas de Booking`. `docs/05-infra-observabilidad.md`
adds the constraints: a multi-stage Dockerfile per service, non-root execution, Actuator health,
configuration by environment variables, the SPA behind a simple reverse proxy, and a local k3d
deployment with Deployment + Service per API, ConfigMap and Secret, Ingress, differentiated
readiness/liveness probes, requests/limits, PVCs for stateful dependencies and Booking scaled to
two replicas. Helm, ArgoCD, Istio and a remote cluster are explicitly out of scope.

Before this phase the repository had no image at all: `compose.yaml` only started PostgreSQL,
RabbitMQ and Redis (profile `core`), and every service ran from `bootRun` or a `bootJar` started by
`scripts/dev-up.sh`. The services exposed `health,info` only; `payment-simulator` had no Actuator.

Two decisions had to be taken together: how the SPA keeps one origin in the containers, and where
the stateful dependencies live in the cluster.

## Decision

1. **One parameterised multi-stage image for the five services.** `docker/service.Dockerfile`
   takes `ARG SERVICE` (the Gradle project name) and builds `:${SERVICE}:bootJar` in an
   `eclipse-temurin:21-jdk-alpine` stage with a BuildKit cache mount for `GRADLE_USER_HOME`, then
   copies the single runnable jar into `eclipse-temurin:21-jre-alpine` and runs it as uid `10001`.
   `-x test` is deliberate: the suite needs a Docker socket (Testcontainers) and an image build must
   not have one. `curl` is installed in the runtime image only because Compose healthchecks call
   `/actuator/health` from inside the container; Kubernetes probes are HTTP calls from the kubelet
   and need no binary. The service port stays an environment concern (`*_PORT`), so the image
   carries no `EXPOSE`.
2. **The SPA ships in two runtime targets with one build.** `frontend/Dockerfile` builds the bundle
   once (`node:24-alpine`, `npm ci`) and offers `runtime-proxy` and `runtime-static`.
   Compose uses `runtime-proxy`: `nginx:1.29-alpine` on `:80`, with upstream hostnames rendered by
   the official `envsubst` entrypoint, so the proxy rewrite (`/api/catalog/x` → `/api/x`) is the
   same mapping `frontend/vite.config.ts` performs in development. k3d uses `runtime-static`:
   `nginxinc/nginx-unprivileged:1.29-alpine` on `:8080`, serving only the bundle. nginx cannot bind
   `:80` as a non-root user without keeping `NET_BIND_SERVICE`, and the all-capabilities-dropped
   pod is worth more than the extra target.
3. **One origin in the cluster too, owned by the Ingress.** Three Traefik `ReplacePathRegex`
   middlewares strip `/api/catalog`, `/api/booking` and `/api/notification` to `/api`, and four
   Ingress objects route those prefixes to their Service while `/` reaches the SPA. Because the
   `runtime-static` container never resolves a service hostname, it starts even before its peers
   exist — the crash-loop-on-DNS-failure that a proxying nginx would have in Kubernetes is avoided
   instead of hidden. The browser sees the same URL layout in `vite dev`, Compose and k3d.
4. **Probes: Boot 4.2 defaults plus the database in readiness.** Boot 4.2 enables the `liveness`
   and `readiness` groups by default (`management.endpoint.health.probes.enabled` is `true`), so
   the only change is `management.endpoint.health.group.readiness.include: readinessState,db` for
   the four services that own a database. A replica that cannot read its schema must leave the
   Service; liveness must not (a restart cannot fix a missing database, and restarting every
   replica during a database outage is a cascading failure). Redis and the broker stay out of
   readiness on purpose: Redis is a throttle that fails open (ADR 0011) and a broker restart must
   not drain the whole service. `ActuatorProbeTest` pins both the paths and the membership,
   `scripts/k3d-verify.sh` proves the behaviourally observable half by scaling PostgreSQL to zero.
5. **Compose keeps two profiles.** `core` (PostgreSQL, RabbitMQ, Redis) and `app` (the six images).
   Compose does not enable the profile of a dependency automatically, so `scripts/stack-up.sh`
   starts both. Only the front door publishes a port (`APP_PORT`, default `8080`); the five
   services stay on the Compose network. Builds run with `COMPOSE_PARALLEL_LIMIT=1` because the five
   builds share one Gradle cache mount.
6. **k3d is self-contained.** PostgreSQL (StatefulSet + PVC, the same idempotent init script as a
   ConfigMap), RabbitMQ (StatefulSet + PVC) and Redis (Deployment without a volume) run inside the
   cluster, so the cluster needs nothing from Compose and the two stacks can be run independently.
   Redis has no PVC because a restart is allowed to lose the queue and the tokens (ADR 0011).
   Credentials are never committed: `scripts/k3d-up.sh` creates the `eventflow-secrets` Secret from
   `.env`. PostgreSQL and RabbitMQ only read those values when their volumes are initialised, so
   changing a password on an existing cluster also requires deleting the corresponding PVC (and the
   data with it); the Secret alone does not rotate it.
7. **Booking gets two replicas without new coordination.** The hold is one conditional SQL statement,
   the outbox relay and the expiry worker claim with `FOR UPDATE SKIP LOCKED`, and admission pops
   queue members atomically in Lua, so two replicas serve at once. The phase gate is therefore a
   proof, not new code: `scripts/k3d-verify.sh` shows `2/2` ready, a token minted through replica A
   honoured by replica B (shared Redis), twenty concurrent holds across both replicas on ten units
   ending `10 held / 0 available / 0 sold`, a deleted pod replaced while the Ingress keeps serving,
   and readiness leaving both replicas when PostgreSQL is scaled to zero with no liveness restart.
8. **Pinned base images.** `eclipse-temurin:21-jdk-alpine`, `eclipse-temurin:21-jre-alpine`,
   `node:24-alpine`, `nginx:1.29-alpine`, `nginxinc/nginx-unprivileged:1.29-alpine` (all verified on
   Docker Hub when adopted), plus the already pinned `postgres:18-alpine`,
   `rabbitmq:4-management-alpine` and `redis:8-alpine`.

## Consequences

- The first `docker compose --profile app build` downloads Gradle and the dependencies into the
  cache mount (minutes); later builds reuse them, and a source change only reruns `bootJar`.
- The image build cannot run the test suite by design, so CI or the developer runs
  `./gradlew build` before building images; `scripts/stack-verify.sh` and `scripts/k3d-verify.sh`
  then exercise the built artifact end to end.
- Two frontend targets mean two nginx configurations to keep aligned on the SPA fallback; the API
  rewrite lives in exactly one place per environment (nginx template or Ingress middleware).
- PostgreSQL and RabbitMQ are single-replica StatefulSets on `local-path` storage: correct for a
  local portfolio demo, not a high-availability story, and the ADR does not claim one.
- Port `8090` hosts the k3d Ingress so both stacks can run at the same time; `8080` belongs to
  Compose.
- Metrics, dashboards and traces are Phase 7; nothing here exposes Prometheus or OTLP.
