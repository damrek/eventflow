#!/usr/bin/env bash
# Runs one k6 scenario from docs/performance/k6 against the already-running Compose stack.
#
#   ./scripts/load-test.sh read-path
#   ./scripts/load-test.sh reservation-contention
#   VUS=40 CAPACITY=50 ./scripts/load-test.sh reservation-contention
#
# The stack must be up (`./scripts/stack-up.sh`); this script never starts it, because a load test
# against a cold JVM measures the JVM warming up. The result object is written to
# docs/performance/results/ and committed, so a published number always traces back to a command.
set -euo pipefail

cd "$(dirname "$0")/.."

SCENARIO="${1:-read-path}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:2.4.0}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

case "$SCENARIO" in
  read-path | reservation-contention) ;;
  *)
    echo "usage: $0 [read-path|reservation-contention]" >&2
    exit 2
    ;;
esac

if ! curl -fsS --max-time 5 "$BASE_URL/healthz" >/dev/null 2>&1; then
  echo "the stack is not answering at $BASE_URL; run ./scripts/stack-up.sh first" >&2
  exit 1
fi

mkdir -p docs/performance/results
RESULT_FILE="$(date +%Y-%m-%dT%H-%M-%S)-${SCENARIO}.json"

env_args=(-e "BASE_URL=${BASE_URL}" -e "SCENARIO=${SCENARIO}" -e "RESULT_FILE=${RESULT_FILE}")
for name in VUS CAPACITY ITERATIONS; do
  if [ -n "${!name:-}" ]; then
    env_args+=(-e "${name}=${!name}")
  fi
done

echo "running ${SCENARIO} against ${BASE_URL} with ${K6_IMAGE}"
echo "result: docs/performance/results/${RESULT_FILE}"

# --network host keeps the base URL identical on the host and inside the container; the scripts are
# mounted read-only, and the container runs as the invoking user so the result file is not root-owned.
# The summary k6 prints is kept next to the JSON object, so the numbers a document quotes are in the
# repository, not only in a scrollback buffer.
docker run --rm --network host \
  --user "$(id -u):$(id -g)" \
  -v "${PWD}/docs/performance/k6:/scripts:ro" \
  -v "${PWD}/docs/performance/results:/results" \
  "${env_args[@]}" \
  "${K6_IMAGE}" run "/scripts/${SCENARIO}.js" 2>&1 |
  tee "docs/performance/results/${RESULT_FILE%.json}.txt"
