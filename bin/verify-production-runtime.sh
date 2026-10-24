#!/usr/bin/env bash
# Inspect an already running production Compose stack; never mutate it.
# Signed manifest is validated before using its image digest values.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DG_RELEASE_MANIFEST:?}"
: "${DG_RELEASE_EXPECTED_COMMIT:?}"
: "${DG_DEPLOY_STAGE:?}"
actual="$(git rev-parse --verify HEAD)"
[[ "$actual" == "$DG_RELEASE_EXPECTED_COMMIT" ]] || {
  echo "NO_GO: release checkout SHA mismatch" >&2;exit 1;
}
bash bin/verify-signed-production-release.sh >/dev/null
python3 - "$DG_RELEASE_MANIFEST" <<'PY'
import json,os,subprocess,sys
manifest=json.load(open(sys.argv[1],encoding="utf-8"))
stage=os.environ["DG_DEPLOY_STAGE"]
if stage not in ("closed","readonly","approval","write"):
 sys.exit("NO_GO: unknown stage")
expected={
 "gateway":manifest["images"]["gateway"],
 "workflow":manifest["images"]["workflow"],
 "web":manifest["images"]["web"],
 "public":os.getenv("DG_CADDY_IMAGE","")
}
for x in ("gateway","workflow","web","public"):
 name="DG_"+("CADDY" if x=="public" else x.upper())+"_IMAGE"
 if os.getenv(name)!=expected[x]:
  sys.exit("NO_GO: deployed image input diverges from signed manifest: "+x)
roles=["gateway","web","public"]
if stage in ("approval","write"):
 roles.insert(1,"workflow")
for role in roles:
 name="dataguard-production-"+role+"-1"
 process=subprocess.run(["docker","inspect",name],capture_output=True,text=True,timeout=12)
 if process.returncode:
  sys.exit("NO_GO: missing deployed container: "+name)
 result=json.loads(process.stdout)
 if len(result)!=1:sys.exit("NO_GO: ambiguous container")
 c=result[0];h=c["HostConfig"];config=c["Config"]
 if not c["State"].get("Running"):sys.exit("NO_GO: stopped container: "+role)
 if config.get("Image")!=expected[role]:
  sys.exit("NO_GO: runtime image differs from signed manifest: "+role)
 if not h.get("ReadonlyRootfs") or "no-new-privileges:true" not in (h.get("SecurityOpt") or []):
  sys.exit("NO_GO: live runtime hardening failed: "+role)
 if "ALL" not in (h.get("CapDrop") or []) or not h.get("Memory") or not h.get("NanoCpus") or not h.get("PidsLimit"):
  sys.exit("NO_GO: live resource/capability limits missing: "+role)
 if role in ("gateway","workflow","web") and h.get("PortBindings"):
  sys.exit("NO_GO: private service port published: "+role)
 env=dict(s.split("=",1) for s in config.get("Env",[]) if "=" in s)
 if any(k in env for k in ("PG_PLATFORM_PASSWORD","PG_FLOWABLE_PASSWORD","POSTGRES_PASSWORD")):
  sys.exit("NO_GO: Docker Config.Env directly contains DB secrets: "+role)
 if role=="gateway":
  for key in ("DG_ENABLE_READ_EXECUTION","DG_ENABLE_WRITE_EXECUTION","DG_WRITE_QUEUE_ENABLED","DG_WRITE_RECOVERY_ENABLED"):
   value=env.get(key,"false").lower()
   want=("true" if (key=="DG_ENABLE_READ_EXECUTION" and stage!="closed")
         or (key!="DG_ENABLE_READ_EXECUTION" and stage=="write") else "false")
   if value!=want:sys.exit("NO_GO: wrong runtime "+key+" for "+stage)
 if role=="workflow":
  if env.get("FLOWABLE_OUTBOX_ENABLED","false")!=("true" if stage in ("approval","write") else "false"):
   sys.exit("NO_GO: wrong Outbox state")
  if env.get("FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED")!="false" or env.get("FLOWABLE_SCHEMA_UPDATE")!="false":
   sys.exit("NO_GO: unsafe Flowable settings")
 print("RUNTIME_VERIFIED: "+role)
print("PRODUCTION_RUNTIME_SIGNED_IMAGE_AND_POLICY_PASS")
PY
