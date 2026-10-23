#!/usr/bin/env bash
# Read-only release preflight. Does not deploy, mutate databases or print secrets.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
echo "MCP DataGuard release preflight"
git rev-parse --verify HEAD >/dev/null
if ! git diff --quiet || ! git diff --cached --quiet; then
 echo "BLOCKED: working tree contains uncommitted modifications" >&2
 exit 1
fi
if [[ "${DG_ENABLE_READ_EXECUTION:-false}" != "false" ]]; then
 echo "BLOCKED: read execution must remain disabled before certified deployment" >&2
 exit 1
fi
if [[ "${FLOWABLE_OUTBOX_ENABLED:-false}" != "false" ]]; then
 echo "BLOCKED: workflow consumer requires separate event-recovery certification" >&2
 exit 1
fi
if [[ "${DG_ENABLE_WRITE_EXECUTION:-false}" != "false" ]]; then
 echo "BLOCKED: write execution must remain disabled" >&2
 exit 1
fi
command -v docker >/dev/null || { echo "BLOCKED: Docker missing" >&2; exit 1; }
docker compose version >/dev/null || { echo "BLOCKED: Docker Compose missing" >&2; exit 1; }
bash -n bin/verify-dev-worktree.sh
bash -n bin/verify-isolated-postgres.sh
# Check artifact inputs without evaluating real deployment secrets.
test -f deploy/compose.yaml && test -f deploy/compose.dev.yaml
test -f dataguard-server/src/main/resources/application.yml
grep -q 'allow-read-execution:' dataguard-server/src/main/resources/application.yml
test -f dataguard-workflow/src/main/resources/application.yml
echo "PASS: source, Compose tooling, scripts and safety flags"
echo "PRECHECK_ONLY: independent auth, PostgreSQL/TLS, Flowable and audit acceptance tests are still required"
echo "NO_DEPLOYMENT_PERFORMED"
