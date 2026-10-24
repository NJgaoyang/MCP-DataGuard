#!/usr/bin/env python3
"""Explicitly approved HTTPS MCP read-only load probe. Never issues a write.

Only run against named, certified targets under change control. The one-time
health/anonymous 80-request smoke is not a production load qualification.
No credential, SQL or returned database row is printed or persisted.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import re
import ssl
import stat
import sys
import time
import urllib.parse
import urllib.request
import uuid

MAX_BYTES=1024*1024

def validate_target(url, approved_host):
    u=urllib.parse.urlsplit(url)
    if (u.scheme!="https" or u.hostname!=approved_host or
        u.username or u.password or u.query or u.fragment or
        u.path not in ("", "/mcp") or u.port not in (None,443)):
        raise ValueError("Approved exact HTTPS MCP target required")
    if not re.fullmatch(r"[a-zA-Z0-9.-]{4,253}",approved_host):
        raise ValueError("Invalid approved host")
    return urllib.parse.urlunsplit(("https",approved_host,"/mcp","",""))

def safe_select(sql):
    value=sql.strip()
    if not re.match(r"(?is)^SELECT\s",value) or ";" in value or len(value)>8192:
        raise ValueError("Only one bounded SELECT is allowed")
    return value

def token_from_file(name):
    p=Path(name)
    if not p.is_absolute() or p.is_symlink() or not p.is_file():
        raise ValueError("Token file must be a regular absolute path")
    if stat.S_IMODE(p.stat().st_mode)!=0o600:
        raise ValueError("Token file must be mode 0600")
    raw=p.read_text().strip()
    if len(raw)<32 or len(raw)>1024 or "\n" in raw:
        raise ValueError("Invalid token file")
    return raw

def rpc_message(payload):
    text=payload.decode("utf-8")
    messages=[]
    for line in text.splitlines():
        if line.startswith("data:"):
            messages.append(json.loads(line[5:].strip()))
    if not messages:
        messages=[json.loads(text)]
    return messages

def call(url,auth,context,message,session=""):
    content=json.dumps(message,separators=(",",":")).encode()
    h={"Authorization":"Bearer "+auth,"Content-Type":"application/json",
       "Accept":"application/json, text/event-stream",
       "MCP-Protocol-Version":"2025-03-26"}
    if session:h["Mcp-Session-Id"]=session
    req=urllib.request.Request(url,data=content,method="POST",headers=h)
    # Exclude HTTP proxy vars. A redirect could leak Authorization, forbid it.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs):return None
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),
        NoRedirect(),urllib.request.HTTPSHandler(context=context))
    with opener.open(req,timeout=15) as result:
        if result.status!=200:raise ValueError("Bad HTTP status")
        data=result.read(MAX_BYTES+1)
        if len(data)>MAX_BYTES:raise ValueError("Oversized MCP response")
        messages=rpc_message(data)
        if any("error" in m or m.get("result",{}).get("isError") is True
               for m in messages if isinstance(m,dict)):
            raise ValueError("MCP tool failure")
        ident=message["id"]
        if not any(m.get("id")==ident and "result" in m for m in messages):
            raise ValueError("Incomplete MCP JSON-RPC result")
        return result.headers.get("Mcp-Session-Id","")

def probe(url,auth,context,datasource,database,sql,page_size):
    start=time.monotonic()
    init={"jsonrpc":"2.0","id":1,"method":"initialize",
        "params":{"protocolVersion":"2025-03-26","capabilities":{},
        "clientInfo":{"name":"ProductionReadLoadProbe","version":"1.0"}}}
    session=call(url,auth,context,init)
    task={"jsonrpc":"2.0","id":2,"method":"tools/call",
      "params":{"name":"execute_readonly","arguments":{
      "datasourceId":datasource,"database":database,"sql":sql,
      "parameters":[],"pageSize":page_size}}}
    call(url,auth,context,task,session)
    return (time.monotonic()-start)*1000

def percentile95(samples):
    if not samples:return 0.
    arr=sorted(samples)
    return arr[max(0,(95*len(arr)+99)//100-1)]

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--url",required=True)
    p.add_argument("--datasource",required=True)
    p.add_argument("--database",required=True)
    p.add_argument("--sql-file",required=True)
    p.add_argument("--requests",type=int,default=80)
    p.add_argument("--workers",type=int,default=4)
    p.add_argument("--qps",type=float,default=8)
    p.add_argument("--max-p95-ms",type=float,default=3000)
    p.add_argument("--max-error-rate",type=float,default=0)
    p.add_argument("--page-size",type=int,default=10)
    a=p.parse_args()
    if os.environ.get("DG_LOAD_APPROVED_TARGET")!="yes":
        raise ValueError("Explicit DG_LOAD_APPROVED_TARGET=yes required")
    if not(20<=a.requests<=1000 and 1<=a.workers<=12 and
           0<a.qps<=20 and 100<=a.max_p95_ms<=30000 and
           0<=a.max_error_rate<=0.05 and 1<=a.page_size<=100):
        raise ValueError("Load safety bounds exceeded")
    uuid.UUID(a.datasource)
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}",a.database):
        raise ValueError("Invalid database")
    url=validate_target(a.url,os.environ["DG_LOAD_APPROVED_HOST"])
    auth=token_from_file(os.environ["DG_LOAD_TOKEN_FILE"])
    sql=safe_select(Path(a.sql_file).read_text())
    context=ssl.create_default_context()
    ca=os.getenv("DG_LOAD_CA_FILE")
    if ca:context.load_verify_locations(cafile=ca)
    successes=[]
    errors=0
    start=time.monotonic()
    with ThreadPoolExecutor(max_workers=a.workers) as executor:
        futures=[]
        for i in range(a.requests):
            deadline=start+i/a.qps
            delay=deadline-time.monotonic()
            if delay>0:time.sleep(delay)
            futures.append(executor.submit(probe,url,auth,context,
                          a.datasource,a.database,sql,a.page_size))
        for f in as_completed(futures):
            try:successes.append(f.result())
            except Exception:errors+=1
    rate=errors/a.requests
    p95=percentile95(successes)
    print("MCP_CERTIFIED_READ_LOAD requests={} success={} errors={} p95_ms={:.1f} elapsed_s={:.1f}".format(
         a.requests,len(successes),errors,p95,time.monotonic()-start))
    if rate>a.max_error_rate or not successes or p95>a.max_p95_ms:
        print("MCP_CERTIFIED_READ_LOAD_NO_GO",file=sys.stderr)
        return 1
    print("MCP_CERTIFIED_READ_LOAD_TECHNICAL_PASS_REQUIRES_APPROVED_EXTERNAL_EVIDENCE")
    return 0

if __name__=="__main__":
    try:sys.exit(main())
    except (ValueError,OSError,KeyError) as e:
        print("MCP_CERTIFIED_READ_LOAD_NO_GO: "+type(e).__name__,file=sys.stderr)
        sys.exit(2)
