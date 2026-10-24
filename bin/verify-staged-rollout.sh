#!/usr/bin/env bash
# Read-only environment policy gate for explicit, independently authorized rollout.
# Never changes feature flags, containers, target databases or releases.
set -euo pipefail
: "${DG_DEPLOY_STAGE:?Set DG_DEPLOY_STAGE=closed|readonly|approval|write}"
stage="$DG_DEPLOY_STAGE"
check_flag(){
  local var="$1" expected="$2"
  if [[ "${!var:-false}" != "$expected" ]]; then
    echo "NO_GO: $var must be $expected for stage $stage" >&2
    exit 1
  fi
}
reject_dynamic(){
  check_flag FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED false
}
require_hosts(){
  local value="$1" name="$2"
  if [[ -z "$value" || "$value" == *'*'* || "$value" == *' '* || "$value" == *, || "$value" == ,* ]]; then
    echo "NO_GO: $name must be an explicit non-wildcard host allowlist" >&2
    exit 1
  fi
}
case "$stage" in
  closed)
    check_flag DG_ENABLE_READ_EXECUTION false
    check_flag DG_ENABLE_WRITE_EXECUTION false
    check_flag DG_WRITE_QUEUE_ENABLED false
    check_flag DG_WRITE_RECOVERY_ENABLED false
    check_flag FLOWABLE_OUTBOX_ENABLED false
    reject_dynamic
    echo "STAGED_ROLLOUT_CLOSED_PASS"
    ;;
  readonly|approval|write)
    # Cryptographic approval and evidence validation cannot be bypassed by a
    # developer setting environment flags. The signed manifest is bound to
    # the source commit currently checked out for the proposed release.
    [[ -n "${DG_RELEASE_EXPECTED_COMMIT:-}" ]] || {
      echo "NO_GO: missing signed release commit" >&2; exit 1;
    }
    actual="$(git rev-parse --verify HEAD)"
    [[ "$actual" == "$DG_RELEASE_EXPECTED_COMMIT" ]] || {
      echo "NO_GO: checked out commit differs from signed release" >&2; exit 1;
    }
    bash "$(dirname "$0")/verify-signed-production-release.sh"
    reject_dynamic
    check_flag DG_ENABLE_READ_EXECUTION true
    require_hosts "${DG_CONNECT_TEST_ALLOW_HOSTS:-}" DG_CONNECT_TEST_ALLOW_HOSTS
    case "$stage" in
      readonly)
        check_flag DG_ENABLE_WRITE_EXECUTION false
        check_flag DG_WRITE_QUEUE_ENABLED false
        check_flag DG_WRITE_RECOVERY_ENABLED false
        check_flag FLOWABLE_OUTBOX_ENABLED false
        ;;
      approval)
        check_flag DG_ENABLE_WRITE_EXECUTION false
        check_flag DG_WRITE_QUEUE_ENABLED false
        check_flag DG_WRITE_RECOVERY_ENABLED false
        check_flag FLOWABLE_OUTBOX_ENABLED true
        ;;
      write)
        check_flag DG_ENABLE_WRITE_EXECUTION true
        check_flag DG_WRITE_QUEUE_ENABLED true
        check_flag DG_WRITE_RECOVERY_ENABLED true
        check_flag FLOWABLE_OUTBOX_ENABLED true
        require_hosts "${DG_WRITE_ALLOWED_HOSTS:-}" DG_WRITE_ALLOWED_HOSTS
        ;;
    esac
    echo "STAGED_ROLLOUT_${stage^^}_POLICY_PASS"
    ;;
  *)
    echo "NO_GO: unknown stage '$stage'" >&2
    exit 2
    ;;
esac
