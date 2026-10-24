#!/usr/bin/env python3
"""Tenant-aggregate incident notification, disabled unless explicitly configured.

HTTPS-only, exact hostname allowlist, globally routable destination,
HMAC-SHA256 signed JSON, retry-on-next-run and deduplicated recovery event.
Never sends SQL text, user identities, table names, row values or secrets.
"""
import datetime as dt
import fcntl
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import stat
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

SQL = """
SELECT
 (SELECT count(*) FROM platform.dg_execution_task WHERE task_state='UNKNOWN'),
 (SELECT count(*) FROM platform.dg_alert_event e JOIN platform.dg_alert_rule r
      ON r.org_id=e.org_id AND r.id=e.rule_id
  WHERE e.event_state='OPEN' AND r.severity IN ('HIGH','CRITICAL')),
 (SELECT count(*) FROM platform.dg_outbox_event WHERE state='FAILED')
"""

def require_file(name):
    path=Path(os.environ[name])
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise ValueError("Unsafe HMAC secret path")
    if stat.S_IMODE(path.stat().st_mode)!=0o600:
        raise ValueError("HMAC secret must be 0600")
    key=path.read_bytes().rstrip(b"\n")
    if len(key)<32 or len(key)>512:
        raise ValueError("HMAC secret needs at least 256 bits")
    return key

def safe_url():
    url=os.environ["DG_ALERT_WEBHOOK_URL"]
    host=os.environ["DG_ALERT_WEBHOOK_HOST"]
    parsed=urllib.parse.urlsplit(url)
    if (parsed.scheme!="https" or parsed.hostname!=host or
        parsed.port not in (None,443) or parsed.username or parsed.password or
        parsed.fragment or not re.fullmatch(r"[a-zA-Z0-9.-]{4,253}",host) or
        host.startswith(".") or host.endswith(".")):
        raise ValueError("HTTPS endpoint must match configured external hostname")
    for result in socket.getaddrinfo(host,443,type=socket.SOCK_STREAM):
        if not ipaddress.ip_address(result[4][0]).is_global:
            raise ValueError("Webhook DNS resolved to non-public network")
    return url

def read_counts():
    container=os.environ.get("DG_ALERT_DB_CONTAINER","deploy-postgres-1")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,100}",container):
        raise ValueError("Invalid database container")
    completed=subprocess.run(
        ["docker","exec","-u","postgres",container,"psql","-X","-A","-t",
         "-U","dataguard_bootstrap","-d","dataguard","-c",SQL],
        capture_output=True,text=True,timeout=10,check=True)
    result=completed.stdout.strip().split("|")
    if len(result)!=3 or any(not value.isdecimal() for value in result):
        raise ValueError("Unexpected database counters")
    counts=dict(zip(("unknownWrites","criticalAlerts","deadLetters"),map(int,result)))
    if any(value>1000000000 for value in counts.values()):
        raise ValueError("Out-of-range counter")
    return counts

def main():
    try:
        # Validate the webhook before reading database data.
        target=safe_url()
        key=require_file("DG_ALERT_WEBHOOK_HMAC_FILE")
        state=Path(os.environ["DG_ALERT_STATE_DIRECTORY"])
        if not state.is_absolute() or state.is_symlink() or not state.is_dir() or stat.S_IMODE(state.stat().st_mode)!=0o700:
            raise ValueError("State directory must be 0700")
        lock=state/".notification.lock"
        with lock.open("a+b") as fd:
            os.chmod(lock,0o600)
            fcntl.flock(fd.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
            previous=state/"last.json"
            if previous.is_symlink():
                raise ValueError("Symlinked alert state")
            old=json.loads(previous.read_text()) if previous.exists() else None
            counts=read_counts()
            active=any(counts.values())
            if not active and old is None:
                print("OPERATIONAL_ALERT_HEALTHY_NO_NOTIFICATION")
                return 0
            canonical=json.dumps(counts,sort_keys=True,separators=(",",":"))
            fingerprint=hashlib.sha256(canonical.encode()).hexdigest()
            if old is not None and old.get("fingerprint")==fingerprint:
                print("OPERATIONAL_ALERT_DEDUPLICATED")
                return 0
            notice={
                "source":"MCP-DataGuard",
                "event":"OPEN" if active else "RECOVERED",
                "counts":counts,
                "fingerprint":fingerprint,
                "observedAt":dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            }
            payload=json.dumps(notice,sort_keys=True,separators=(",",":")).encode()
            signature=hmac.new(key,payload,hashlib.sha256).hexdigest()
            request=urllib.request.Request(
                target,data=payload,method="POST",
                headers={"Content-Type":"application/json",
                  "X-DataGuard-Signature-SHA256":"sha256="+signature,
                  "X-DataGuard-Event-Id":fingerprint})
            with urllib.request.urlopen(request,timeout=8) as response:
                if not 200<=response.status<300:
                    raise ValueError("Webhook rejected notification")
            # State advances only after a confirmed 2xx response.
            tempfile_path=state/("last.json."+str(os.getpid())+".tmp")
            try:
                with tempfile_path.open("x") as out:
                    json.dump({"fingerprint":fingerprint},out)
                os.chmod(tempfile_path,0o600)
                os.replace(tempfile_path,previous)
            finally:
                tempfile_path.unlink(missing_ok=True)
            print("OPERATIONAL_ALERT_NOTIFICATION_DELIVERED")
            return 0
    except (OSError,KeyError,ValueError,TimeoutError,subprocess.SubprocessError,
            json.JSONDecodeError,urllib.error.URLError) as error:
        print("OPERATIONAL_ALERT_NOTIFICATION_FAILED: "+type(error).__name__,file=sys.stderr)
        return 1

if __name__=="__main__":
    sys.exit(main())
