#!/usr/bin/env bash
# Static guards for the Phase 8 CI and lint contract (S1, S2). The pipeline itself can only be
# observed green after a push, which is a human decision, so these checks pin what the workflow must
# contain: the same commands this repository runs by hand, pinned actions, no load tests per PR and
# no secret.
#
#   ./scripts/ci-invariants-test.sh
set -euo pipefail

cd "$(dirname "$0")/.."

workflow=.github/workflows/ci.yml
failures=0
require() { # <file> <extended-regex> <message>
  if grep -Eq "$2" "$1"; then printf 'ok   %s\n' "$3"
  else printf 'FAIL %s (%s)\n' "$3" "$1" >&2; failures=$((failures + 1)); fi
}
forbid() {
  if grep -Eq "$2" "$1"; then printf 'FAIL %s (%s)\n' "$3" "$1" >&2; failures=$((failures + 1))
  else printf 'ok   %s\n' "$3"; fi
}

# S2: the workflow exists and runs where a reviewer needs it.
require "$workflow" '^on:$' 'CI declares its triggers'
require "$workflow" '^  push:$' 'CI runs on push'
require "$workflow" 'branches: \[main\]' 'CI runs on main'
require "$workflow" '^  pull_request:$' 'CI runs on every pull request'
require "$workflow" 'cancel-in-progress: true' 'CI cancels superseded runs'
require "$workflow" '^  contents: read$' 'CI keeps read-only permissions'
forbid "$workflow" 'secrets\.' 'CI needs no secret'
forbid "$workflow" 'push: true' 'CI never pushes an image'

# S2: the three gates exist, with the commands a developer runs locally.
for job in 'frontend:' 'backend:' 'images:' 'frontend-image:'; do
  require "$workflow" "^  ${job}$" "CI has the ${job%:} job"
done
require "$workflow" 'run: npm ci$' 'frontend installs the committed lockfile'
forbid "$workflow" 'run: npm install' 'frontend never falls back to npm install'
for step in 'run: npm run format:check$' 'run: npm run lint$' 'run: npm test$' 'run: npm run build$'; do
  require "$workflow" "$step" "frontend runs ${step#run: }"
done
require "$workflow" 'run: \./gradlew build --stacktrace$' 'backend runs the whole Gradle build'
require "$workflow" "java-version: '21'" 'backend uses JDK 21'
require "$workflow" 'uses: gradle/actions/setup-gradle@' 'backend caches the Gradle wrapper'
require "$workflow" 'if: failure\(\)' 'test reports are uploaded only on failure'
require "$workflow" 'service: \[catalog, booking, notification, payment, payment-simulator\]' \
  'every service image is built'
require "$workflow" 'target: runtime-proxy' 'the Compose frontend target is built'

# S2: every action is pinned to an exact released version, never a floating tag.
if grep -E '^\s+uses: ' "$workflow" | grep -Ev 'uses: [^@]+@v[0-9]+\.[0-9]+\.[0-9]+$' >/dev/null; then
  printf 'FAIL an action is not pinned to exact versions\n' >&2; failures=$((failures + 1))
else printf 'ok   every action is pinned to an exact version\n'; fi

# S2: load tests need a running stack; they never run in the PR pipeline (the header comment may
# name them; an executed step may not).
if grep -E '^[[:space:]]+(run|uses):' "$workflow" | grep -Eq 'k6|load-test'; then
  printf 'FAIL CI runs a load test\n' >&2; failures=$((failures + 1))
else printf 'ok   CI runs no load test\n'; fi

# S1: the lint and format gates exist and are exact pins, not ranges.
for dep in eslint @eslint/js typescript-eslint eslint-plugin-react-hooks eslint-config-prettier \
           prettier globals; do
  require frontend/package.json "\"${dep}\": \"[0-9]" "${dep} is pinned exactly"
done
require frontend/package.json '"lint": "eslint \."' 'npm run lint exists'
require frontend/package.json '"format:check": "prettier --check \."' 'npm run format:check exists'
require frontend/package.json '"format": "prettier --write \."' 'npm run format exists'
# S1: an exact pin in package.json is only worth something if the lockfile agrees with it.
if python3 - frontend/package.json frontend/package-lock.json <<'PY'
import json, sys

manifest = json.load(open(sys.argv[1]))
locked = json.load(open(sys.argv[2]))["packages"][""].get("devDependencies", {})
problems = [
    name
    for name, version in manifest["devDependencies"].items()
    if not version[0].isdigit() or (name in locked and locked[name] != version)
]
if problems:
    print("not an exact pin, or the lockfile disagrees: " + ", ".join(sorted(problems)), file=sys.stderr)
    sys.exit(1)
PY
then
  printf 'ok   every devDependency is an exact pin the lockfile matches\n'
else
  printf 'FAIL a frontend devDependency is a range or the lockfile disagrees\n' >&2
  failures=$((failures + 1))
fi
for f in frontend/eslint.config.js frontend/.prettierrc.json frontend/.prettierignore; do
  if [ -f "$f" ]; then printf 'ok   %s exists\n' "$f"
  else printf 'FAIL %s is missing\n' "$f" >&2; failures=$((failures + 1)); fi
done
require frontend/.prettierignore '^dist/$' 'Prettier ignores the build output'
require frontend/eslint.config.js 'eslint-config-prettier' 'ESLint defers formatting to Prettier'
require frontend/eslint.config.js 'react-hooks' 'ESLint keeps the hooks rules'

# S2: when Docker is available, the workflow is parsed by actionlint (the same version CI reviewers
# run); without Docker the static checks above still hold. A non-zero exit is reported with its own
# reason, because actionlint also refuses to run outside a git checkout (exit 3).
if command -v docker >/dev/null 2>&1; then
  if lint_output=$(docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12 2>&1); then
    printf 'ok   actionlint accepts the workflow\n'
  else
    printf 'FAIL actionlint did not accept the workflow:\n%s\n' "$lint_output" >&2
    failures=$((failures + 1))
  fi
else printf 'skip actionlint (docker is not available)\n'; fi

if [ "$failures" -ne 0 ]; then
  printf '%d CI invariant(s) broken\n' "$failures" >&2
  exit 1
fi
echo 'PASS: CI invariants hold'
