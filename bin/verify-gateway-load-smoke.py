#!/usr/bin/env python3
"""Bounded, read-only gateway availability and auth-denial smoke test.

A safe PRE-PRODUCTION smoke check, not a representative SQL workload or a
certified peak-capacity test. Never uses credentials or writes any business
data. Requests 200 /actuator/health and 401 /mcp with no Authorization header.
Max 8 workers, 120 total requests, 10 RPS (conservatively throttled).
"""
import argparse
import concurrent.futures as cf
import math
import os
import ssl
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request

def measure(base,path,expected,ctx):
    started=time.perf_counter()
    request=urllib.request.Request(base+path,headers={"Accept":"application/json"})
    try:
        with urllib.request.urlopen(request,timeout=6,context=ctx) as response:
            code=response.status
            response.read(256)
    except urllib.error.HTTPError as error:
        code=error.code
        error.read(256)
    return code==expected,(time.perf_counter()-started)*1000,code

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--base",default="http://127.0.0.1:18082")
    parser.add_argument("--requests",type=int,default=80)
    parser.add_argument("--workers",type=int,default=4)
    parser.add_argument("--p95-limit-ms",type=int,default=4000)
    args=parser.parse_args()
    target=urllib.parse.urlsplit(args.base)
    if (target.scheme not in ("http","https") or not target.hostname or
        target.username or target.password or target.path.strip("/") or
        target.query or target.fragment):
        parser.error("Use a base URL with no path or credentials")
    if args.workers<1 or args.workers>8 or args.requests<10 or args.requests>120:
        parser.error("Refusing unsafe load settings")
    if args.p95_limit_ms<100 or args.p95_limit_ms>10000:
        parser.error("Unsafe latency budget")
    # Only local development by default. External targets require an explicit
    # approval switch and must still obey the strict request-rate ceiling.
    if target.hostname not in ("localhost","127.0.0.1","::1") and (
            os.environ.get("DG_ALLOW_APPROVED_LOAD_TARGET")!="yes"):
        parser.error("External load targets require explicit approval")
    ctx=ssl.create_default_context()
    futures=[]
    started=time.monotonic()
    with cf.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for i in range(args.requests):
            p,e=("/actuator/health",200) if i%2==0 else ("/mcp",401)
            futures.append(pool.submit(measure,args.base,p,e,ctx))
            # Bound total request rate, including slow responses.
            deadline=started+(i+1)/10
            remaining=deadline-time.monotonic()
            if remaining>0:time.sleep(remaining)
        results=[f.result() for f in futures]
    latencies=sorted(x[1] for x in results)
    p95=latencies[max(0,math.ceil(len(latencies)*.95)-1)]
    failures=[x for x in results if not x[0]]
    print(f"SAFE_GATEWAY_LOAD_SMOKE requests={len(results)} workers={args.workers} "+
          f"p50_ms={statistics.median(latencies):.1f} p95_ms={p95:.1f} "+
          f"errors={len(failures)}")
    if failures or p95>args.p95_limit_ms:
        raise SystemExit("SAFE_GATEWAY_LOAD_SMOKE_FAILED")
    print("SAFE_GATEWAY_LOAD_SMOKE_PASS")
if __name__=="__main__":
    main()
