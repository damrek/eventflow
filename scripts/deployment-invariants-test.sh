#!/usr/bin/env bash
# Static guards for the Phase 6 deployment contract (S1, S3, S6, S7, and the defects recorded as L9
# and L10). They need no cluster and no containers: every failure is an edit that would break the
# running stack, so they run next to the test suite.
#
#   ./scripts/deployment-invariants-test.sh
set -euo pipefail

cd "$(dirname "$0")/.."

failures=0
require() { # <file> <extended-regex> <message>
  if grep -Eq "$2" "$1"; then printf 'ok   %s\n' "$3"
  else printf 'FAIL %s (%s)\n' "$3" "$1" >&2; failures=$((failures + 1)); fi
}
forbid() {
  if grep -Eq "$2" "$1"; then printf 'FAIL %s (%s)\n' "$3" "$1" >&2; failures=$((failures + 1))
  else printf 'ok   %s\n' "$3"; fi
}

# S7: Booking is the only workload with two replicas and a rollout may not drop one.
require deploy/k8s/apps/booking.yaml '^  replicas: 2$' 'booking runs two replicas'
require deploy/k8s/apps/booking.yaml 'maxUnavailable: 0' 'booking rollout keeps a replica available'
require deploy/k8s/apps/booking.yaml 'terminationGracePeriodSeconds: 30' 'booking gets a graceful-stop window'
for f in deploy/k8s/apps/*.yaml; do
  require "$f" 'runAsNonRoot: true' "$f runs non-root"
  require "$f" 'imagePullPolicy: IfNotPresent' "$f uses IfNotPresent"
  require "$f" '^          resources:$' "$f sets resources"
done

# L9: service links inject <SERVICE>_PORT=tcp://... and break the *_PORT integers.
for f in deploy/k8s/apps/*.yaml deploy/k8s/infra/*.yaml; do
  if grep -q '^kind: \(Deployment\|StatefulSet\)$' "$f"; then
    require "$f" 'enableServiceLinks: false' "$f disables service links"
  fi
done

# S3: readiness covers db for the four services that own one, never for the simulator.
for svc in catalog booking notification payment; do
  require "services/$svc/src/main/resources/application.yml" 'include: readinessState,db' "$svc readiness includes db"
  forbid "services/$svc/src/main/resources/application.yml" '^[[:space:]]+liveness:' "$svc adds no liveness group"
done
forbid services/payment-simulator/src/main/resources/application.yml 'include:.*db' 'simulator readiness keeps the default'
require services/payment-simulator/build.gradle.kts 'spring-boot-starter-actuator' 'simulator ships Actuator'

# S7: the probes point at the Actuator paths the services expose.
for f in deploy/k8s/apps/catalog.yaml deploy/k8s/apps/booking.yaml deploy/k8s/apps/notification.yaml \
         deploy/k8s/apps/payment.yaml deploy/k8s/apps/payment-simulator.yaml; do
  require "$f" 'path: /actuator/health/readiness' "$f probes readiness"
  require "$f" 'path: /actuator/health/liveness' "$f probes liveness"
done

# S2/S7: the static frontend must listen where the pod probes and the Service point.
require frontend/Dockerfile 'nginxinc/nginx-unprivileged:1\.29-alpine AS runtime-static' 'k3d frontend is unprivileged nginx'
require frontend/nginx/static.conf 'listen 8080;' 'static frontend listens on 8080'
require deploy/k8s/apps/frontend.yaml 'containerPort: 8080' 'frontend pod exposes 8080'
require deploy/k8s/apps/frontend.yaml 'path: /healthz' 'frontend probes /healthz'

# S2: the Compose template keeps the Vite rewrite and the SPA fallback.
require frontend/nginx/default.conf.template 'rewrite \^/api/catalog/\(\.\*\)\$ /api/\$1 break;' 'catalog prefix is rewritten'
require frontend/nginx/default.conf.template 'try_files \$uri \$uri/ /index.html;' 'SPA fallback is present'

# S6/S7: one origin in the cluster, and the stateful-dependency storage rules.
for svc in catalog booking notification; do
  require deploy/k8s/middlewares.yaml "regex: \^/api/$svc" "$svc middleware strips the prefix"
  require deploy/k8s/ingress.yaml "path: /api/$svc" "$svc ingress routes the prefix"
done
require deploy/k8s/infra/postgres.yaml 'mountPath: /var/lib/postgresql[[:space:]]*$' 'postgres mounts the PG18 volume root'
forbid deploy/k8s/infra/postgres.yaml 'mountPath: /var/lib/postgresql/data' 'postgres never mounts .../data'
forbid deploy/k8s/infra/rabbitmq.yaml '^[[:space:]]+fsGroup:' 'rabbitmq keeps no fsGroup (L10)'
forbid deploy/k8s/infra/redis.yaml 'volumeClaimTemplates' 'redis stays ephemeral'

# S6: credentials are never a manifest.
if git rev-parse --git-dir >/dev/null 2>&1 && git ls-files --error-unmatch .env >/dev/null 2>&1; then
  printf 'FAIL .env is tracked\n' >&2; failures=$((failures + 1))
else printf 'ok   .env is not tracked\n'; fi

# Host-exposure guards: every published port stays on loopback, the untracked .env never enters a
# build context, and the ignore rules cover the env variants and credential files.
#
# The port check is per entry on purpose: a form-specific pattern passed a bare `- 9000:9000` and an
# explicit `0.0.0.0:` binding, which is exactly the exposure it exists to catch.
ports_seen=0
ports_bad=0
while IFS= read -r entry; do
  [ -n "$entry" ] || continue
  ports_seen=$((ports_seen + 1))
  case "$entry" in
    *'127.0.0.1:'*) ;;
    *) printf 'FAIL port is not loopback-bound: %s\n' "$entry" >&2; ports_bad=$((ports_bad + 1)) ;;
  esac
done <<PORTS
$(awk '/^    ports:/ { inside = 1; next } inside && /^      - / { print; next } inside && !/^      / { inside = 0 }' compose.yaml)
PORTS
if [ "$ports_bad" -eq 0 ] && [ "$ports_seen" -gt 0 ]; then
  printf 'ok   every published port is loopback-bound (%s)\n' "$ports_seen"
else
  if [ "$ports_seen" -eq 0 ]; then
    printf 'FAIL no published port was parsed from compose.yaml\n' >&2
    failures=$((failures + 1))
  fi
  failures=$((failures + ports_bad))
fi
require .dockerignore '^\.env$' '.dockerignore keeps .env out of the build context'
require .dockerignore '^\*\*/\.env$' '.dockerignore keeps a nested .env out of the build context'
require frontend/.dockerignore '^\.env$' 'the frontend build context keeps .env out'
require .gitignore '^\.env\.\*$' '.gitignore covers the env file variants'
require .gitignore '^!\.env\.example$' '.gitignore still tracks the env template'
require .gitignore '^\*\.pem$' '.gitignore covers *.pem keys'
require .gitignore '^\*\.key$' '.gitignore covers *.key files'
require .gitignore '^\*\.jks$' '.gitignore covers keystores'
require .gitignore '^local\.properties$' '.gitignore covers local.properties'

if [ "$failures" -ne 0 ]; then
  printf '%d deployment invariant(s) broken\n' "$failures" >&2
  exit 1
fi
echo 'PASS: deployment invariants hold'
