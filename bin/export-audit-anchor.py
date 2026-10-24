#!/usr/bin/env python3
"""Privacy-preserving, HMAC-authenticated external audit checkpoint delivery.

Uses the *same* validated HTTPS/DNS-pin/no-redirect transport as operational
alerts. The external receiver must be independent and append-only; a local
2xx receipt alone cannot certify immutability. Disabled if unconfigured.
"""
import datetime as dt
import fcntl
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import urllib.request

def digest_streams(rows):
    """Only pseudonymous organization digests and stream head hashes enter root."""
    canonical=[]
    for line in rows:
        parts=line.strip().split("|")
        if len(parts)!=4 or not re.fullmatch(r"[0-9a-f]{32}",parts[0]):
            raise ValueError("Invalid organization digest")
        org,stream,seq,head=parts
        if not re.fullmatch(r"[a-zA-Z0-9_.-]{1,100}",stream):
            raise ValueError("Invalid stream name")
        if not seq.isdecimal() or int(seq)>10**12 or not re.fullmatch(r"[0-9a-f]{64}",head):
            raise ValueError("Invalid audit head")
        canonical.append([org,stream,int(seq),head])
    canonical.sort()
    value=json.dumps(canonical,separators=(",",":"),ensure_ascii=True)
    return hashlib.sha256(value.encode()).hexdigest(),len(canonical)

def load_sender():
    source=Path(__file__).with_name("notify-operational-incidents.py")
    spec=importlib.util.spec_from_file_location("dg_hardened_sender",source)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def heads():
    container=os.environ.get("DG_AUDIT_DB_CONTAINER","deploy-postgres-1")
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}",container):
        raise ValueError("Invalid local database container")
    sql="""SELECT md5(org_id::text),stream_key,last_seq,last_hash
             FROM platform.dg_audit_stream ORDER BY org_id,stream_key"""
    p=subprocess.run(["docker","exec","-u","postgres",container,"psql",
                      "-X","-A","-t","-v","ON_ERROR_STOP=1",
                      "-U","dataguard_bootstrap","-d","dataguard","-c",sql],
                     capture_output=True,text=True,timeout=12,check=True)
    return [line for line in p.stdout.splitlines() if line]

def main():
    try:
        url=os.environ["DG_AUDIT_ANCHOR_URL"]
        host=os.environ["DG_AUDIT_ANCHOR_HOST"]
        # Reuse fail-closed notification TLS implementation; no proxies,
        # redirects or non-global DNS addresses are permitted.
        os.environ["DG_ALERT_WEBHOOK_URL"]=url
        os.environ["DG_ALERT_WEBHOOK_HOST"]=host
        secure=load_sender()
        url,remote_ip=secure.safe_url()
        secret_file=os.environ["DG_AUDIT_ANCHOR_HMAC_FILE"]
        os.environ["DG_ALERT_WEBHOOK_HMAC_FILE"]=secret_file
        secret=secure.require_file("DG_ALERT_WEBHOOK_HMAC_FILE")
        state=Path(os.environ["DG_AUDIT_ANCHOR_STATE_DIR"])
        if not state.is_absolute() or state.is_symlink() or not state.is_dir() or stat.S_IMODE(state.stat().st_mode)!=0o700:
            raise ValueError("Anchor state must be directory mode 0700")
        checksum,count=digest_streams(heads())
        lock=state/".lock"
        with lock.open("a+b") as fd:
            os.chmod(lock,0o600)
            fcntl.flock(fd.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
            checkpoint=state/"last.sha256"
            if checkpoint.is_symlink():raise ValueError("Untrusted state link")
            if checkpoint.exists() and checkpoint.read_text().strip()==checksum:
                print("AUDIT_ANCHOR_DEDUPLICATED")
                return 0
            payload=json.dumps({
              "kind":"MCP_DATAGUARD_AUDIT_CHECKPOINT_V1",
              "checkpointSha256":checksum,
              "streamCount":count,
              "observedAt":dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            },separators=(",",":"),sort_keys=True).encode()
            signature=hmac.new(secret,payload,hashlib.sha256).hexdigest()
            req=urllib.request.Request(url,data=payload,method="POST",
                 headers={"Content-Type":"application/json",
                   "X-DataGuard-Signature-SHA256":"sha256="+signature,
                   "X-DataGuard-Event-Id":checksum})
            opener=urllib.request.build_opener(
                urllib.request.ProxyHandler({}),
                secure.NoRedirect(),secure.PinnedHTTPS(remote_ip))
            with opener.open(req,timeout=8) as response:
                if not 200<=response.status<300:
                    raise ValueError("Anchor receiver rejected request")
            temp=state/("last.sha256."+str(os.getpid())+".tmp")
            try:
                with temp.open("x") as out:out.write(checksum+"\n")
                os.chmod(temp,0o600)
                os.replace(temp,checkpoint)
            finally:
                temp.unlink(missing_ok=True)
            print("AUDIT_ANCHOR_EXTERNAL_DELIVERY_PASS")
            return 0
    except (OSError,KeyError,ValueError,TimeoutError,subprocess.SubprocessError) as err:
        print("AUDIT_ANCHOR_NO_GO: "+type(err).__name__,file=sys.stderr)
        return 1

if __name__=="__main__":
    sys.exit(main())
