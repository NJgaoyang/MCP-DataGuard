#!/usr/bin/env bash
# Fail-closed static checks for the hardened Compose overlay.
# This never runs, builds, stops or modifies containers.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
docker compose --profile workflow \
 --env-file deploy/.env.dev.example --env-file deploy/.env.public.example \
 -f deploy/compose.yaml -f deploy/compose.dev.yaml \
 -f deploy/compose.public.yaml -f deploy/compose.hardened.yaml \
 config --format json | python3 -c '
import json,sys
c=json.load(sys.stdin)["services"]
for service in ("gateway","workflow","web","public","postgres"):
 s=c[service]
 assert "no-new-privileges:true" in s.get("security_opt",[]),service
 assert int(s.get("pids_limit",0))>0,service
 assert int(s.get("mem_limit",0))>0,service
 assert float(s.get("cpus",0))>0,service
 assert s.get("logging",{}).get("options",{}).get("max-size")=="10m",service
 assert s.get("logging",{}).get("options",{}).get("max-file")=="5",service
assert c["postgres"].get("memswap_limit")==c["postgres"]["mem_limit"], "PostgreSQL swap not bounded"
for service in ("gateway","workflow","web","public"):
 s=c[service]
 assert s.get("read_only") is True,service
 assert s.get("tmpfs"),service
for service in ("gateway","workflow","web"):
 assert "ALL" in c[service].get("cap_drop",[]),service
for service in ("gateway","workflow"):
 assert not c[service].get("ports"),service
assert all(item.get("host_ip")=="127.0.0.1" for item in c["postgres"]["ports"])
assert all(item.get("host_ip")=="127.0.0.1" for item in c["web"]["ports"])
assert c["gateway"]["environment"]["DG_ENABLE_READ_EXECUTION"]=="false"
assert c["workflow"]["environment"]["FLOWABLE_OUTBOX_ENABLED"]=="false"
assert c["workflow"]["environment"]["FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED"]=="false"
print("HARDENED_COMPOSE_STATIC_GATE_PASS")
'
bash -n bin/backup-postgres-encrypted.sh bin/verify-encrypted-backup-restore.sh
# Confirm critical fail-closed settings in the codebase without a privileged DB.
grep -Fq 'DG_ENABLE_READ_EXECUTION: "false"' deploy/compose.dev.yaml
grep -Fq 'FLOWABLE_OUTBOX_ENABLED: ${FLOWABLE_OUTBOX_ENABLED:-false}' deploy/compose.dev.yaml
echo "PRODUCTION_SECURITY_CONFIGURATION_PRECHECK_PASS"
