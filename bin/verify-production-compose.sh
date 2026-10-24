#!/usr/bin/env bash
# Fail closed: static check only. Never deploys or reads/prints secret contents.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DG_GATEWAY_IMAGE:?}"
: "${DG_WORKFLOW_IMAGE:?}"
: "${DG_WEB_IMAGE:?}"
: "${DG_CADDY_IMAGE:?}"
: "${DG_PUBLIC_HOST:?}"
: "${DG_PLATFORM_JDBC_URL:?}"
: "${DG_FLOWABLE_JDBC_URL:?}"
: "${DG_PLATFORM_USER:?}"
: "${DG_FLOWABLE_USER:?}"
: "${DG_PROD_PLATFORM_PASSWORD_FILE:?}"
: "${DG_PROD_FLOWABLE_PASSWORD_FILE:?}"
: "${DG_PROD_POSTGRES_CA_FILE:?}"
python3 - <<'PY'
import os,re,sys,pathlib,urllib.parse
for n in ("DG_GATEWAY_IMAGE","DG_WORKFLOW_IMAGE","DG_WEB_IMAGE","DG_CADDY_IMAGE"):
 v=os.environ[n]
 if not re.fullmatch(r"[a-zA-Z0-9_./:-]+@sha256:[0-9a-f]{64}",v):
  sys.exit("NO_GO: image must be pinned @sha256: "+n)
for n in ("DG_PLATFORM_JDBC_URL","DG_FLOWABLE_JDBC_URL"):
 u=os.environ[n]
 if not u.startswith("jdbc:postgresql://") or "@" in u:
  sys.exit("NO_GO: only external authenticated PostgreSQL JDBC URL allowed: "+n)
 q=urllib.parse.parse_qs(urllib.parse.urlsplit(u.removeprefix("jdbc:")).query)
 if q.get("sslmode")!=["verify-full"]:
  sys.exit("NO_GO: JDBC must use sslmode=verify-full: "+n)
 if q.get("sslrootcert")!=["/run/secrets/postgres_ca"]:
  sys.exit("NO_GO: signed trusted PostgreSQL CA must be mounted: "+n)
 if "127.0.0.1" in u or "localhost" in u or "postgres:5432" in u:
  sys.exit("NO_GO: runtime DB must be separately administered: "+n)
host=os.environ["DG_PUBLIC_HOST"]
if not re.fullmatch(r"(?=.{4,253}$)[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]",host) or "." not in host or host.replace(".","").isdigit() or host.endswith(".invalid"):
 sys.exit("NO_GO: real production DNS name required")
for n in ("DG_PROD_PLATFORM_PASSWORD_FILE","DG_PROD_FLOWABLE_PASSWORD_FILE"):
 p=pathlib.Path(os.environ[n])
 if not p.is_absolute() or p.is_symlink() or not p.is_file():
  sys.exit("NO_GO: secret must exist at a regular absolute path: "+n)
 if p.stat().st_uid!=0 or p.stat().st_gid!=10001 or (p.stat().st_mode & 0o777)!=0o640:
  sys.exit("NO_GO: expect root:10001 mode 0640 for non-root JVM: "+n)
 if p.stat().st_size<20:
  sys.exit("NO_GO: secret too short: "+n)
ca=pathlib.Path(os.environ["DG_PROD_POSTGRES_CA_FILE"])
if not ca.is_absolute() or ca.is_symlink() or not ca.is_file() or ca.stat().st_mode & 0o002:
 sys.exit("NO_GO: invalid trusted CA certificate")
if ca.stat().st_size<64 or ca.stat().st_size>65536 or b"BEGIN CERTIFICATE" not in ca.read_bytes()[:1024]:
 sys.exit("NO_GO: PEM certificate required")
PY
docker compose --profile workflow -f deploy/compose.production.yaml config --format json |
python3 -c '
import json,sys
c=json.load(sys.stdin);s=c["services"]
assert not "postgres" in s
assert set(s)=={"gateway","workflow","web","public"}
assert set(c.get("secrets",{}))=={"platform_password","flowable_password","postgres_ca"}
for role in s:
 x=s[role]
 assert "sha256:" in x["image"] and "@sha256:" in x["image"]
 assert x["read_only"] is True
 assert "no-new-privileges:true" in x["security_opt"]
 assert "ALL" in x.get("cap_drop",[])
 assert x.get("pids_limit",0)>0 and x.get("mem_limit",0)>0 and x.get("cpus",0)>0
 assert x.get("logging",{}).get("options",{}).get("max-file")=="5"
 assert not x.get("build")
for role in ("gateway","workflow","web"):
 assert not s[role].get("ports"),role
assert len(s["public"]["ports"])==2
assert set(x["published"] for x in s["public"]["ports"])=={"80","443"}
assert c["services"]["workflow"]["environment"]["FLOWABLE_SCHEMA_UPDATE"]=="false"
assert c["services"]["workflow"]["environment"]["FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED"]=="false"
for role in ("gateway","workflow"):
 assert "PG_PLATFORM_PASSWORD" not in s[role]["environment"]
assert "PG_FLOWABLE_PASSWORD" not in s["workflow"]["environment"]
assert s["gateway"]["entrypoint"][-1]=="gateway"
assert s["workflow"]["entrypoint"][-1]=="workflow"
print("PRODUCTION_COMPOSE_STATIC_SECURITY_PASS")
'
bash -n deploy/launch-with-secrets.sh
echo "PRODUCTION_COMPOSE_NO_DEPLOYMENT_PASS"
