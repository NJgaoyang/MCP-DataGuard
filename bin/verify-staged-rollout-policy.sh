#!/usr/bin/env bash
# Safe negative and closed-state policy regression. Never touches a live service.
set -euo pipefail
cd "$(dirname "$0")/.."
closed_flags=(
  DG_ENABLE_READ_EXECUTION=false DG_ENABLE_WRITE_EXECUTION=false
  DG_WRITE_QUEUE_ENABLED=false DG_WRITE_RECOVERY_ENABLED=false
  FLOWABLE_OUTBOX_ENABLED=false FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED=false
)
env DG_DEPLOY_STAGE=closed "${closed_flags[@]}" bash bin/verify-staged-rollout.sh |
  grep -q '^STAGED_ROLLOUT_CLOSED_PASS$'
must_fail(){
  local label="$1"
  shift
  if env "$@" bash bin/verify-staged-rollout.sh >/dev/null 2>&1; then
    echo "FAIL: $label incorrectly passed" >&2
    exit 1
  fi
}
must_fail no-stage "${closed_flags[@]}"
must_fail unapproved-read DG_DEPLOY_STAGE=readonly "${closed_flags[@]}" DG_ENABLE_READ_EXECUTION=true
must_fail unapproved-approval DG_DEPLOY_STAGE=approval "${closed_flags[@]}" FLOWABLE_OUTBOX_ENABLED=true
must_fail unapproved-write DG_DEPLOY_STAGE=write "${closed_flags[@]}" DG_ENABLE_WRITE_EXECUTION=true
must_fail unknown-stage DG_DEPLOY_STAGE=invalid "${closed_flags[@]}"
must_fail closed-read-misconfiguration DG_DEPLOY_STAGE=closed "${closed_flags[@]}" DG_ENABLE_READ_EXECUTION=true
must_fail closed-workflow-misconfiguration DG_DEPLOY_STAGE=closed "${closed_flags[@]}" FLOWABLE_OUTBOX_ENABLED=true
must_fail closed-dynamic-deploy DG_DEPLOY_STAGE=closed "${closed_flags[@]}" FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED=true
echo "STAGED_ROLLOUT_NEGATIVE_POLICY_PASS"
