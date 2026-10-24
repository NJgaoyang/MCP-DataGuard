#!/usr/bin/env bash
# Read-only inspection of an authorized DataGuard development host.
# Does not inspect/export secret values, recreate containers or touch volumes.
set -euo pipefail
for name in gateway workflow web public;do
 container="deploy-$name-1"
 running="$(docker inspect "$container" --format '{{.State.Running}}')"
 readonly="$(docker inspect "$container" --format '{{.HostConfig.ReadonlyRootfs}}')"
 memory="$(docker inspect "$container" --format '{{.HostConfig.Memory}}')"
 pids="$(docker inspect "$container" --format '{{.HostConfig.PidsLimit}}')"
 security="$(docker inspect "$container" --format '{{json .HostConfig.SecurityOpt}}')"
 [[ "$running" == true && "$readonly" == true ]] ||
   { echo "FAIL $name: runtime missing or not read-only" >&2;exit 1; }
 [[ "$memory" =~ ^[0-9]+$ && "$memory" -gt 0 ]] ||
   { echo "FAIL $name: memory is unbounded" >&2;exit 1; }
 [[ "$pids" =~ ^[0-9]+$ && "$pids" -gt 0 ]] ||
   { echo "FAIL $name: PID count is unbounded" >&2;exit 1; }
 [[ "$security" == *'no-new-privileges:true'* ]] ||
   { echo "FAIL $name: no-new-privileges not enabled" >&2;exit 1; }
 echo "PASS $name: read-only, bounded resources and privilege escalation disabled"
done
health="$(curl -ksS --max-time 10 -o /dev/null -w '%{http_code}' \
 https://110.42.132.44/actuator/health)"
[[ "$health" == 200 ]] || { echo "Gateway HTTP health failed" >&2;exit 1; }
anon="$(curl -ksS --max-time 10 -o /dev/null -w '%{http_code}' \
 https://110.42.132.44/mcp)"
[[ "$anon" == 401 ]] || { echo "Anonymous MCP must be denied" >&2;exit 1; }
flags="$(docker inspect deploy-gateway-1 --format '{{range .Config.Env}}{{println .}}{{end}}')"
[[ "$flags" == *'DG_ENABLE_READ_EXECUTION=false'* ]] ||
  { echo "Read execution enablement must remain false" >&2;exit 1; }
wf="$(docker inspect deploy-workflow-1 --format '{{range .Config.Env}}{{println .}}{{end}}')"
[[ "$wf" == *'FLOWABLE_OUTBOX_ENABLED=false'* &&
   "$wf" == *'FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED=false'* ]] ||
  { echo "Flowable experimental flags must remain off" >&2;exit 1; }
[[ "$(docker inspect deploy-postgres-1 --format '{{.State.Running}}')" == true ]] ||
  { echo "PostgreSQL offline" >&2;exit 1; }
pg_memory="$(docker inspect deploy-postgres-1 --format '{{.HostConfig.Memory}}')"
pg_swap="$(docker inspect deploy-postgres-1 --format '{{.HostConfig.MemorySwap}}')"
pg_pids="$(docker inspect deploy-postgres-1 --format '{{.HostConfig.PidsLimit}}')"
[[ "$pg_memory" =~ ^[0-9]+$ && "$pg_memory" -ge 1073741824 &&
   "$pg_swap" == "$pg_memory" && "$pg_pids" =~ ^[0-9]+$ &&
   "$pg_pids" -gt 0 ]] ||
 { echo "PostgreSQL live resource bounds missing" >&2;exit 1; }
echo "PASS postgres: live CPU/memory/PID limits set; no-new-privileges requires restart"
echo "LIVE_DEV_HARDENING_ACCEPTANCE_PASS"
