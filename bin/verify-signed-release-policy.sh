#!/usr/bin/env bash
# Real ephemeral GPG signing test for the 3-party release gate.
# All identities are temporary test keys and removed on exit.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
chmod 700 "$DIR"
export GNUPGHOME="$DIR/gnupg"
mkdir -m 700 "$GNUPGHOME"
make_signer(){
 local name="$1" id="$2"
 gpg --batch --quiet --pinentry-mode loopback --passphrase '' \
  --quick-generate-key "$name <$id@example.invalid>" ed25519 sign 1d
 gpg --batch --with-colons --list-keys "$id@example.invalid" |
  awk -F: '$1=="fpr" {print $10;exit}'
}
SEC="$(make_signer security dg-security)"
DBA="$(make_signer dba dg-dba)"
BIZ="$(make_signer business dg-business)"
gpg --batch --export "$SEC" "$DBA" "$BIZ" > "$DIR/trusted.gpg"
python3 - "$DIR/release.json" <<'PY'
import datetime,json,sys
now=datetime.datetime.now(datetime.timezone.utc)
m={"schemaVersion":1,"gitCommit":"a"*40,
   "createdAt":now.strftime("%Y-%m-%dT%H:%M:%SZ"),
   "expiresAt":(now+datetime.timedelta(days=3)).strftime("%Y-%m-%dT%H:%M:%SZ"),
   "images":{k:"registry.invalid/dataguard/"+k+"@sha256:"+"1"*64
             for k in ("gateway","workflow","web")},
   "evidence":{k:"artifact://independently-reviewed/"+k
      for k in ("real_mcp_read","live_flowable_identity","physical_write_fault",
       "offsite_object_lock_restore","wal_pitr_restore","penetration_test",
       "production_load_test","secret_rotation","monitoring_and_alerts",
       "production_tls_and_network")}}
open(sys.argv[1],"w").write(json.dumps(m))
PY
sign_all(){
 for pair in "security:$SEC" "dba:$DBA" "business:$BIZ";do
  role="${pair%%:*}";key="${pair#*:}"
  gpg --batch --yes --quiet --detach-sign -u "$key" -o "$DIR/$role.sig" "$DIR/release.json"
 done
}
sign_all
export DG_RELEASE_MANIFEST="$DIR/release.json"
export DG_RELEASE_TRUSTED_KEYRING="$DIR/trusted.gpg"
export DG_RELEASE_EXPECTED_COMMIT="$(printf 'a%.0s' {1..40})"
export DG_RELEASE_SECURITY_SIG="$DIR/security.sig"
export DG_RELEASE_DBA_SIG="$DIR/dba.sig"
export DG_RELEASE_BUSINESS_SIG="$DIR/business.sig"
export DG_RELEASE_SECURITY_FPR="$SEC"
export DG_RELEASE_DBA_FPR="$DBA"
export DG_RELEASE_BUSINESS_FPR="$BIZ"
bash "$ROOT/bin/verify-signed-production-release.sh" > "$DIR/positive.log"
grep -q PRODUCTION_SIGNED_RELEASE_EVIDENCE_GATE_PASS "$DIR/positive.log"
echo "PASS: independent security/DBA/business cryptographic signatures"
sed -i 's/artifact:\\/\\/independently-reviewed/artifact:\\/\\/tampered-evidence-replaced/g' "$DIR/release.json"
if bash "$ROOT/bin/verify-signed-production-release.sh" >"$DIR/tamper.log" 2>&1;then
 echo "FAIL: a tampered evidence manifest was accepted" >&2;exit 1
fi
echo "PASS: changing signed release evidence invalidates approval"
python3 - "$DIR/release.json" <<'PY'
import datetime,json,sys
p=sys.argv[1];m=json.load(open(p))
m["expiresAt"]=(datetime.datetime.now(datetime.timezone.utc)-
                 datetime.timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
open(p,"w").write(json.dumps(m))
PY
sign_all
if bash "$ROOT/bin/verify-signed-production-release.sh" >"$DIR/expired.log" 2>&1;then
 echo "FAIL: valid signatures accepted an expired release" >&2;exit 1
fi
echo "PASS: signed but expired release is rejected"
DG_RELEASE_DBA_FPR="$SEC" \
 bash "$ROOT/bin/verify-signed-production-release.sh" >"$DIR/same-signer.log" 2>&1 &&
 { echo "FAIL: separation of duties bypassed" >&2;exit 1; }
echo "PASS: same signing identity cannot approve multiple functions"
echo "THREE_PARTY_RELEASE_SIGNOFF_POLICY_PASS"
