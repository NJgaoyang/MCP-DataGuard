#!/usr/bin/env bash
# Validate the three-party release signatures before inspecting running containers.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DG_RELEASE_MANIFEST:?}"
: "${DG_RELEASE_EXPECTED_COMMIT:?}"
: "${DG_DEPLOY_STAGE:?}"
[[ "$(git rev-parse --verify HEAD)" == "$DG_RELEASE_EXPECTED_COMMIT" ]] || {
 echo "NO_GO: signed release commit differs from checkout" >&2; exit 1;
}
bash bin/verify-signed-production-release.sh >/dev/null
python3 bin/verify-production-runtime.py
