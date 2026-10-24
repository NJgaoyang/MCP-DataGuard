#!/usr/bin/env bash
# Offline policy test. AWS is replaced by a deliberately strict mock.
# No cloud credentials or network access are needed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
chmod 700 "$DIR"
mkdir -p "$DIR/bin" "$DIR/backups"
chmod 700 "$DIR/backups"
FILE="$DIR/backups/dataguard-dataguard-20261024T100000Z-123.dump.gpg"
printf 'synthetic-encrypted-placeholder-for-policy-only\n' > "$FILE"
chmod 600 "$FILE"
(cd "$DIR/backups" && sha256sum "$(basename "$FILE")" > "$(basename "$FILE").sha256")
chmod 600 "$FILE.sha256"
cat > "$DIR/bin/aws" <<'PY'
#!/usr/bin/env python3
import json,os,sys,pathlib
args=sys.argv[1:]
if args[:2]==['s3api','get-object-lock-configuration']:
 print(json.dumps({"ObjectLockConfiguration":{"ObjectLockEnabled":"Enabled"}}))
elif args[:2]==['s3api','put-object']:
 def get(flag):return args[args.index(flag)+1]
 required={"--if-none-match":"*","--object-lock-mode":"COMPLIANCE",
    "--server-side-encryption":"aws:kms","--checksum-algorithm":"SHA256"}
 assert all(get(flag)==value for flag,value in required.items())
 meta=get("--metadata")
 assert meta.startswith("sha256=") and len(meta)==71
 state={"ObjectLockMode":"COMPLIANCE","ServerSideEncryption":"aws:kms",
    "SSEKMSKeyId":get("--ssekms-key-id"),
    "Metadata":{"sha256":meta[7:]},
    "ObjectLockRetainUntilDate":get("--object-lock-retain-until-date")}
 pathlib.Path(os.environ["MOCK_S3_STATE"]).write_text(json.dumps(state))
 print("{}")
elif args[:2]==['s3api','head-object']:
 state=json.loads(pathlib.Path(os.environ["MOCK_S3_STATE"]).read_text())
 if os.environ.get("MOCK_BAD_REMOTE_ENCRYPTION")=="1":
  state["ServerSideEncryption"]="AES256"
 print(json.dumps(state))
else:raise SystemExit("Unexpected S3 operation: "+str(args[:2]))
PY
chmod 700 "$DIR/bin/aws"
export PATH="$DIR/bin:$PATH"
export MOCK_S3_STATE="$DIR/state.json"
export DG_OFFSITE_ARCHIVE="$FILE"
export DG_OFFSITE_S3_BUCKET="dg-audited-backups-example"
export DG_OFFSITE_S3_KMS_ARN="arn:aws:kms:ap-northeast-1:123456789012:key/11111111-2222-4333-8444-555555555555"
export DG_OFFSITE_S3_RETENTION_DAYS=90
export DG_OFFSITE_S3_PREFIX=dataguard/backup
bash "$ROOT/bin/export-encrypted-backup-s3.sh" > "$DIR/valid.log"
grep -q "OFFSITE_OBJECT_LOCK_COMPLIANCE_UPLOAD_VERIFIED" "$DIR/valid.log"
echo "PASS: create-only immutable GPG-object export verifies KMS and COMPLIANCE"
export MOCK_BAD_REMOTE_ENCRYPTION=1
if bash "$ROOT/bin/export-encrypted-backup-s3.sh" >"$DIR/invalid.log" 2>&1;then
 echo "FAIL: accepted an S3 response without SSE-KMS" >&2;exit 1
fi
unset MOCK_BAD_REMOTE_ENCRYPTION
echo "PASS: downgrade to non-KMS encryption is rejected"
DG_OFFSITE_S3_RETENTION_DAYS=3 \
 bash "$ROOT/bin/export-encrypted-backup-s3.sh" >"$DIR/retention.log" 2>&1 &&
 { echo "FAIL: unsafe retention accepted" >&2;exit 1; }
echo "PASS: short retention rejected before any upload"
# Bad local file checksum must block uploading before contacting S3.
printf 'tamper\n' >> "$FILE"
if bash "$ROOT/bin/export-encrypted-backup-s3.sh" >"$DIR/tampered.log" 2>&1;then
 echo "FAIL: corrupted encrypted archive accepted" >&2;exit 1
fi
echo "PASS: changed archive rejected by SHA256"
echo "OFFSITE_OBJECT_LOCK_POLICY_ACCEPTANCE_PASS"
