#!/usr/bin/env bash
# Production change-control preflight ONLY: no restarts, writes, deploys, database tests.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DG_PRODUCTION_CHANGE_ID:?approved immutable change ID required}"
[[ "$DG_PRODUCTION_CHANGE_ID" =~ ^[A-Za-z0-9_-]{8,128}$ ]] || {
  echo "NO_GO: invalid change ID" >&2;exit 2;
}
: "${DG_RELEASE_EXPECTED_COMMIT:?}"
[[ "$(git rev-parse HEAD)" == "$DG_RELEASE_EXPECTED_COMMIT" ]] || {
  echo "NO_GO: release checkout does not match approval" >&2;exit 2;
}
if [[ -n "$(git status --porcelain)" ]];then
 echo "NO_GO: dirty production release worktree" >&2;exit 2
fi
# Verify live GitHub PR protection, independent review, and all required CI contexts.
python3 bin/check-github-release-governance.py
bash bin/verify-production-compose.sh
bash bin/check-production-readiness.sh
bash bin/verify-signed-production-release.sh
bash bin/verify-staged-rollout.sh
python3 - "$DG_RELEASE_MANIFEST" <<'PY'
import json,os,sys
m=json.load(open(sys.argv[1]))
for role in ("gateway","workflow","web","caddy"):
 k="DG_"+role.upper()+"_IMAGE"
 if os.environ[k]!=m["images"][role]:
  sys.exit("NO_GO: Compose image differs from signed approved manifest: "+role)
print("PRODUCTION_IMAGE_MANIFEST_ALIGNMENT_PASS")
PY
echo "PRODUCTION_PREFLIGHT_PASS_NO_DEPLOYMENT_NO_DATABASE_MUTATION"
