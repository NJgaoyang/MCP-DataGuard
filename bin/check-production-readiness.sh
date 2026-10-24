#!/usr/bin/env bash
# Read-only inventory. Does not perform production approval or inspect secrets.
# Offsite backups are explicitly out of scope.
set -euo pipefail
: "${DG_READINESS_EVIDENCE_DIR:?Provide directory with approval evidence records}"
[[ "$DG_READINESS_EVIDENCE_DIR" = /* && -d "$DG_READINESS_EVIDENCE_DIR" &&
   ! -L "$DG_READINESS_EVIDENCE_DIR" ]] ||
 { echo "NO_GO: missing absolute evidence directory" >&2;exit 2; }
echo "DataGuard production Go/No-Go (offsite backup excluded)"
missing=0
required=(
  flowable-real-users.txt
  flowable-outbox-state-sync.txt
  worker-java-process-chaos.txt
  unknown-two-person-reconciliation.txt
  postgres-read-datasource-certification.txt
  production-credential-rotation.txt
  trusted-tls-and-network.txt
  vulnerability-and-penetration-test.txt
  load-soak-and-rollback.txt
  alert-notification-delivery.txt
  local-gpg-restore-and-retention.txt
  signed-three-party-release.txt
)
for item in "${required[@]}";do
 path="$DG_READINESS_EVIDENCE_DIR/$item"
 if [[ -f "$path" && ! -L "$path" && -s "$path" &&
       "$(stat -c '%a' "$path")" =~ ^(600|640)$ ]];then
  echo "EVIDENCE_PRESENT: $item (requires independent review)"
 else
  echo "PENDING: $item"
  missing=$((missing+1))
 fi
done
if ((missing>0));then
 echo "PRODUCTION_RELEASE_NO_GO pending=$missing"
 exit 1
fi
echo "EVIDENCE_FILES_PRESENT_REQUIRES_HUMAN_VERIFICATION"
echo "IMPORTANT: file presence does not certify contents or authorize deployment."
