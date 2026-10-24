#!/usr/bin/env bash
# Export an already GPG-encrypted PostgreSQL backup to an immutable AWS S3
# Object Lock bucket using an out-of-band IAM role and customer-managed KMS.
# This never handles database plaintext or decryption private keys.
set -euo pipefail
umask 077
for tool in aws python3 sha256sum stat;do
 command -v "$tool" >/dev/null || { echo "Missing $tool" >&2;exit 2; }
done
: "${DG_OFFSITE_ARCHIVE:?absolute .dump.gpg file path required}"
: "${DG_OFFSITE_S3_BUCKET:?Object Lock bucket required}"
: "${DG_OFFSITE_S3_KMS_ARN:?customer-managed KMS ARN required}"
: "${DG_OFFSITE_S3_RETENTION_DAYS:?COMPLIANCE retention days required}"
PREFIX="${DG_OFFSITE_S3_PREFIX:-dataguard/backups}"
[[ "$DG_OFFSITE_ARCHIVE" = /* && "$DG_OFFSITE_ARCHIVE" = *.dump.gpg &&
   -f "$DG_OFFSITE_ARCHIVE" && ! -L "$DG_OFFSITE_ARCHIVE" &&
   "$(stat -c '%a' "$DG_OFFSITE_ARCHIVE")" == 600 ]] ||
 { echo "Encrypted backup must be a regular absolute file with mode 0600" >&2;exit 2; }
[[ -f "$DG_OFFSITE_ARCHIVE.sha256" && ! -L "$DG_OFFSITE_ARCHIVE.sha256" &&
   "$(stat -c '%a' "$DG_OFFSITE_ARCHIVE.sha256")" == 600 ]] ||
 { echo "Private checksum manifest missing" >&2;exit 2; }
[[ "$DG_OFFSITE_S3_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ &&
   "$DG_OFFSITE_S3_BUCKET" != *..* ]] || { echo "Bad bucket" >&2;exit 2; }
[[ "$DG_OFFSITE_S3_KMS_ARN" =~ ^arn:aws:kms:[a-z0-9-]+:[0-9]{12}:key/[A-Fa-f0-9-]{36}$ ]] ||
 { echo "Customer-managed KMS ARN required" >&2;exit 2; }
[[ "$DG_OFFSITE_S3_RETENTION_DAYS" =~ ^[0-9]{2,4}$ ]] ||
 { echo "Retention days must be 30-3650" >&2;exit 2; }
(( 10#$DG_OFFSITE_S3_RETENTION_DAYS >= 30 &&
   10#$DG_OFFSITE_S3_RETENTION_DAYS <= 3650 )) ||
 { echo "Retention days must be 30-3650" >&2;exit 2; }
[[ "$PREFIX" =~ ^[a-z0-9][a-z0-9_/-]{0,128}$ &&
   "$PREFIX" != *..* && "$PREFIX" != */ && "$PREFIX" != *//* ]] ||
 { echo "Bad object prefix" >&2;exit 2; }
NAME="$(basename "$DG_OFFSITE_ARCHIVE")"
[[ "$NAME" =~ ^dataguard-[a-zA-Z0-9_-]+-[0-9]{8}T[0-9]{6}Z-[0-9]+\.dump\.gpg$ ]] ||
 { echo "Unexpected archive name" >&2;exit 2; }
(cd "$(dirname "$DG_OFFSITE_ARCHIVE")" && sha256sum --check --status "$NAME.sha256") ||
 { echo "Encrypted archive hash mismatch" >&2;exit 3; }
SHA="$(sha256sum "$DG_OFFSITE_ARCHIVE" | awk '{print $1}')"
UNTIL="$(python3 - "$DG_OFFSITE_S3_RETENTION_DAYS" <<'PY'
import datetime,sys
days=int(sys.argv[1]); assert 30<=days<=3650
print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=days))
      .strftime('%Y-%m-%dT%H:%M:%SZ'))
PY
)"
KEY="$PREFIX/$NAME"
CONFIG="$(aws s3api get-object-lock-configuration --bucket "$DG_OFFSITE_S3_BUCKET")"
python3 - "$CONFIG" <<'PY'
import json,sys
assert json.loads(sys.argv[1]).get("ObjectLockConfiguration",{}).get("ObjectLockEnabled")=="Enabled"
PY
# --if-none-match '*' enforces create-only even under concurrent upload.
# Never replace an existing locked version, or silently downgrade the retention.
aws s3api put-object --bucket "$DG_OFFSITE_S3_BUCKET" --key "$KEY" \
 --if-none-match '*' --body "$DG_OFFSITE_ARCHIVE" \
 --server-side-encryption aws:kms --ssekms-key-id "$DG_OFFSITE_S3_KMS_ARN" \
 --object-lock-mode COMPLIANCE --object-lock-retain-until-date "$UNTIL" \
 --checksum-algorithm SHA256 --metadata "sha256=$SHA" >/dev/null
HEAD="$(aws s3api head-object --bucket "$DG_OFFSITE_S3_BUCKET" --key "$KEY")"
python3 - "$HEAD" "$SHA" "$DG_OFFSITE_S3_KMS_ARN" "$UNTIL" <<'PY'
import datetime,json,sys
h=json.loads(sys.argv[1]);sha,kms,minimum=sys.argv[2:]
assert h.get("ObjectLockMode")=="COMPLIANCE", "COMPLIANCE retention missing"
assert h.get("ServerSideEncryption")=="aws:kms", "Server-side KMS missing"
assert h.get("SSEKMSKeyId")==kms, "Wrong KMS key"
assert h.get("Metadata",{}).get("sha256")==sha, "Remote checksum metadata mismatched"
def date(s):return datetime.datetime.fromisoformat(s.replace("Z","+00:00"))
assert date(h["ObjectLockRetainUntilDate"])>=date(minimum), "Remote retention too short"
PY
echo "OFFSITE_OBJECT_LOCK_COMPLIANCE_UPLOAD_VERIFIED"
echo "REMOTE_BUCKET=$DG_OFFSITE_S3_BUCKET"
echo "REMOTE_KEY=$KEY"
echo "RETENTION_UNTIL=$UNTIL"
