#!/usr/bin/env python3
"""Plan an explicitly authorized DOWNGRADE of a DataGuard execution stage.

This is intentionally read-only: no Docker commands, DB updates, or retries.
Operators must separately approve/execute/verify each rollback on a real host.
"""
import argparse
import json
import re
import sys
STAGES=("closed","readonly","approval","write")
def plan(current,target,change):
    if current not in STAGES or target not in STAGES:
        raise ValueError("unknown stage")
    if not re.fullmatch(r"[A-Za-z0-9_-]{8,128}",change):
        raise ValueError("change ticket must be explicit and nonempty")
    old=STAGES.index(current)
    new=STAGES.index(target)
    if old<=new:
        raise ValueError("rollback must reduce capabilities, not hold or escalate")
    if old-new!=1 and target!="closed":
        raise ValueError("skip-stage rollback allowed only to emergency closed")
    # Explicitly enumerate allowed flags. It is never safe to just set
    # DG_ENABLE_WRITE_EXECUTION=false while queue and recovery stay true.
    flags={
      "DG_ENABLE_READ_EXECUTION":str(new>=1).lower(),
      "FLOWABLE_OUTBOX_ENABLED":str(new>=2).lower(),
      "DG_ENABLE_WRITE_EXECUTION":str(new>=3).lower(),
      "DG_WRITE_QUEUE_ENABLED":str(new>=3).lower(),
      "DG_WRITE_RECOVERY_ENABLED":str(new>=3).lower(),
      "FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED":"false"
    }
    actions=[
      "Get an authorized maintenance/change window and record the immutable running image digest",
      "Disable write execution, queue and recovery together before any other downgrade action",
      "Quarantine every UNKNOWN attempt; never replay an ambiguous PostgreSQL COMMIT",
      "Confirm pending Flowable Outbox events are drained or independently accounted for",
      "Apply target-stage environment flags using the approved immutable images",
      "Verify live container state with signed release manifest and verify-production-runtime.sh",
      "Check external TLS, read result restrictions, alert delivery and local backup after restart",
      "Record DBA/security/business change decision and preserve rollback evidence"
    ]
    return {"kind":"DATAGUARD_ROLLBACK_PLAN_V1","changeId":change,
            "from":current,"to":target,"featureFlags":flags,
            "commandsExecuted":False,"sqlWritesPerformed":False,
            "automaticUnknownRetryAllowed":False,
            "warning":"Already committed SQL changes are not undone by this rollback",
            "operatorActions":actions}
def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--from-stage",required=True,choices=STAGES)
    parser.add_argument("--to-stage",required=True,choices=STAGES)
    parser.add_argument("--change-id",required=True)
    args=parser.parse_args()
    print(json.dumps(plan(args.from_stage,args.to_stage,args.change_id),
                     indent=2,sort_keys=True))
if __name__=="__main__":
    try:main()
    except ValueError as error:
        print("ROLLBACK_PLAN_NO_GO: "+type(error).__name__,file=sys.stderr)
        sys.exit(2)
