#!/usr/bin/env python3
"""Read-only, redacted inventory of a running DataGuard host; never an approval."""
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

NAME=re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}$")
SERVICES=("gateway","workflow","web","public","postgres")
TIMERS=("dataguard-postgres-backup.timer","dataguard-backup-health.timer",
        "dataguard-backup-retention.timer","dataguard-operations-alert.timer")

def exec_safe(args):
    try:
        return subprocess.run(args,check=False,text=True,capture_output=True,
                              timeout=10)
    except (OSError,subprocess.TimeoutExpired):
        return None

def docker_status(role):
    value=os.getenv("DG_RUNTIME_"+role.upper()+"_CONTAINER","deploy-"+role+"-1")
    if not NAME.fullmatch(value):
        return {"role":role,"error":"invalid container name"}
    p=exec_safe(["docker","inspect",value])
    if p is None or p.returncode!=0:
        return {"role":role,"error":"container unavailable"}
    try:
        data=json.loads(p.stdout)[0]
        host=data["HostConfig"]
        state=data["State"]
        opt=host.get("SecurityOpt") or []
        status={
            "role":role,
            "status":state.get("Status","unknown"),
            "running":bool(state.get("Running")),
            "readOnlyRootfs":bool(host.get("ReadonlyRootfs")),
            "noNewPrivileges":"no-new-privileges:true" in opt,
            "memoryLimitBytes":int(host.get("Memory") or 0),
            "cpuNano":int(host.get("NanoCpus") or 0),
            "pidLimit":int(host.get("PidsLimit") or 0),
            "dockerImageId":data.get("Image",""),
            "securityFlagsSatisfied":False
        }
        status["securityFlagsSatisfied"]=(
            status["running"] and
            (role=="postgres" or status["readOnlyRootfs"]) and
            status["noNewPrivileges"] and
            status["memoryLimitBytes"]>0 and
            status["cpuNano"]>0 and
            status["pidLimit"]>0
        )
        return status
    except (ValueError,KeyError,TypeError,IndexError):
        return {"role":role,"error":"invalid docker inspection"}

def timer_status(timer):
    p=exec_safe(["systemctl","is-active",timer])
    return {"timer":timer,
            "active":p is not None and p.returncode==0 and p.stdout.strip()=="active"}

def main():
    services=[docker_status(role) for role in SERVICES]
    timers=[timer_status(timer) for timer in TIMERS]
    evidence={
        "type":"READ_ONLY_RUNTIME_INVENTORY_NOT_A_PRODUCTION_APPROVAL",
        "collectedAtUtc":datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "host":os.uname().nodename,
        "services":services,
        "timers":timers,
        "productionEvidencePresent":os.path.isdir(
            os.getenv("DG_PRODUCTION_EVIDENCE_DIR","/etc/dataguard/release-evidence")),
        "alertEnvironmentConfigured":os.path.isfile(
            "/etc/dataguard/operations-alert.env"),
        "signedReleaseManifestConfigured":os.path.isfile(
            "/etc/dataguard/production-release.json"),
        "notes":[
            "No secrets, environment values, user records or SQL are collected",
            "Live runtime settings do not prove backup restore, pager delivery, signed release or business acceptance",
            "Offsite backup is intentionally excluded by documented business scope"
        ]
    }
    print(json.dumps(evidence,indent=2,sort_keys=True))
    failures=[s.get("role","?") for s in services if not s.get("securityFlagsSatisfied")]
    failures.extend(t["timer"] for t in timers if not t["active"])
    if not evidence["alertEnvironmentConfigured"]:
        failures.append("notification receiver")
    if not evidence["signedReleaseManifestConfigured"]:
        failures.append("signed release manifest")
    if not evidence["productionEvidencePresent"]:
        failures.append("external production evidence")
    print("RUNTIME_READINESS_NO_GO: "+", ".join(failures) if failures
          else "RUNTIME_CONFIGURATION_PRESENT_PENDING_INDEPENDENT_SIGNOFF",
          file=sys.stderr)
    return 1 if failures else 0

if __name__=="__main__":
    sys.exit(main())
