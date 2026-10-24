#!/usr/bin/env bash
# Three distinct cryptographic authorizations are required to release.
# No default bypass, no test-token mode, no network calls or deployment.
set -euo pipefail
umask 077
for name in DG_RELEASE_MANIFEST DG_RELEASE_TRUSTED_KEYRING \
 DG_RELEASE_EXPECTED_COMMIT DG_RELEASE_SECURITY_SIG DG_RELEASE_DBA_SIG \
 DG_RELEASE_BUSINESS_SIG DG_RELEASE_SECURITY_FPR DG_RELEASE_DBA_FPR \
 DG_RELEASE_BUSINESS_FPR;do
 [[ -n "${!name:-}" ]] || { echo "NO_GO: missing $name" >&2;exit 2; }
done
[[ "$DG_RELEASE_EXPECTED_COMMIT" =~ ^[a-f0-9]{40}$ ]] ||
 { echo "NO_GO: release commit must be full git SHA" >&2;exit 2; }
for f in "$DG_RELEASE_MANIFEST" "$DG_RELEASE_TRUSTED_KEYRING" \
 "$DG_RELEASE_SECURITY_SIG" "$DG_RELEASE_DBA_SIG" "$DG_RELEASE_BUSINESS_SIG";do
 [[ "$f" = /* && -f "$f" && ! -L "$f" ]] ||
  { echo "NO_GO: require regular absolute evidence files" >&2;exit 2; }
done
declare -A SIGNER=(
 [security]="$DG_RELEASE_SECURITY_FPR"
 [dba]="$DG_RELEASE_DBA_FPR"
 [business]="$DG_RELEASE_BUSINESS_FPR"
)
declare -A SIGNATURE=(
 [security]="$DG_RELEASE_SECURITY_SIG"
 [dba]="$DG_RELEASE_DBA_SIG"
 [business]="$DG_RELEASE_BUSINESS_SIG"
)
for role in security dba business;do
 fpr="${SIGNER[$role]}"
 [[ "$fpr" =~ ^[A-Fa-f0-9]{40}$ ]] ||
  { echo "NO_GO: expected full $role signer fingerprint" >&2;exit 2; }
done
[[ "${SIGNER[security],,}" != "${SIGNER[dba],,}" &&
   "${SIGNER[security],,}" != "${SIGNER[business],,}" &&
   "${SIGNER[dba],,}" != "${SIGNER[business],,}" ]] ||
 { echo "NO_GO: the same person cannot sign multiple roles" >&2;exit 2; }
for role in security dba business;do
 # gpgv only trusts keys loaded in the explicit approval keyring.
 output="$(gpgv --keyring "$DG_RELEASE_TRUSTED_KEYRING" --status-fd 1 \
   "${SIGNATURE[$role]}" "$DG_RELEASE_MANIFEST" 2>/dev/null)" ||
   { echo "NO_GO: $role signature is invalid" >&2;exit 3; }
 fingerprint="$(printf '%s\n' "$output" | awk '$1=="[GNUPG:]" && $2=="VALIDSIG" {print $3;exit}')"
 [[ "${fingerprint^^}" == "${SIGNER[$role]^^}" ]] ||
  { echo "NO_GO: wrong signer for $role" >&2;exit 3; }
 echo "PASS: independent $role release signature"
done
python3 - "$DG_RELEASE_MANIFEST" "$DG_RELEASE_EXPECTED_COMMIT" <<'PY'
import datetime,json,pathlib,re,sys
m=json.loads(pathlib.Path(sys.argv[1]).read_text())
assert m.get("schemaVersion")==1
assert m.get("gitCommit")==sys.argv[2], "Manifest release SHA mismatch"
images=m.get("images",{})
for name in ("gateway","workflow","web"):
 assert re.fullmatch(r"[a-zA-Z0-9_./:-]+@sha256:[0-9a-f]{64}",
                     images.get(name,"")),name+" digest missing"
evidence=m.get("evidence",{})
gates=("real_mcp_read","live_flowable_identity","physical_write_fault",
       "local_encrypted_backup_restore","wal_pitr_restore","penetration_test",
       "production_load_test","secret_rotation","monitoring_and_alerts",
       "production_tls_and_network")
for gate in gates:
 value=evidence.get(gate)
 assert isinstance(value,str) and 12<=len(value)<=2048 and not value.isspace(),gate
# Every proof is submitted and attested by independent signers. This checks
# presence, recency and cryptographic accountability, not proof authenticity.
def parse(key):
 value=m.get(key,"")
 assert isinstance(value,str) and value.endswith("Z"),key
 return datetime.datetime.fromisoformat(value.replace("Z","+00:00"))
now=datetime.datetime.now(datetime.timezone.utc)
created,expires=parse("createdAt"),parse("expiresAt")
assert created<=now+datetime.timedelta(minutes=5), "Future-dated evidence"
assert created>=now-datetime.timedelta(days=7), "Stale evidence"
assert expires>now, "Expired approval"
assert expires<=created+datetime.timedelta(days=14), "Approval window too long"
print("PRODUCTION_SIGNED_RELEASE_EVIDENCE_GATE_PASS")
PY
