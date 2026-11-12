#!/usr/bin/env bash
# Before a release: build the image from this tree, start a fresh dev + prod stack beside the demo
# (its own git remote, brokers and volumes, other ports), drive the whole life of a rule through it in
# Chrome, check the layout of every screen, and take the stack down again.
#
#   e2e/run.sh                 everything; screenshots and a report in e2e/out
#   KEEP=1 e2e/run.sh          leaves the stack up afterwards (dev :7180, prod :7181)
#   SKIP_UP=1 e2e/run.sh       runs against a stack already up (after KEEP=1)
#   ASSISTANT_URL=http://host.docker.internal:11434/v1 e2e/run.sh   with the assistant panel
set -euo pipefail
cd "$(dirname "$0")"

export E2E_PROJECT=${E2E_PROJECT:-camunda-e2e}
if [ "$E2E_PROJECT" = camunda-eval ]; then
  echo "e2e: refusing to run as camunda-eval, the demo's project: it would wipe the demo" >&2
  exit 2
fi
export RULES_IMAGE=${RULES_IMAGE:-camunda-eval:e2e}
export DEV_PORT=${DEV_PORT:-7180} PROD_PORT=${PROD_PORT:-7181}
export KAFKA_DEV_PORT=${KAFKA_DEV_PORT:-19192} KAFKA_PROD_PORT=${KAFKA_PROD_PORT:-29192}
export ASSISTANT_URL=${ASSISTANT_URL:-}
export DEV_URL=http://localhost:$DEV_PORT PROD_URL=http://localhost:$PROD_PORT
export OUT=${OUT:-$PWD/out}
compose() { docker compose -f ../docker-compose.yml -p "$E2E_PROJECT" "$@"; }
export -f compose 2>/dev/null || true

if [ -z "${SKIP_UP:-}" ]; then
  # Fresh every time: no remote, topics or data left from a run before.
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  compose up -d --build rules-dev rules-prod
fi
for url in "$DEV_URL" "$PROD_URL"; do
  for _ in $(seq 1 90); do
    curl -fs "$url/api/app" >/dev/null 2>&1 && break
    sleep 2
  done
  curl -fs "$url/api/app" >/dev/null || { echo "e2e: $url did not come up" >&2; compose logs --tail 80 >&2; exit 1; }
done

[ -d node_modules ] || npm install --no-audit --no-fund
rm -rf "$OUT" && mkdir -p "$OUT"
status=0
node lifecycle.mjs || status=$?
node layout.mjs || status=$?

if [ -z "${KEEP:-}" ] && [ -z "${SKIP_UP:-}" ]; then
  compose down -v --remove-orphans >/dev/null 2>&1 || true
fi
exit $status
