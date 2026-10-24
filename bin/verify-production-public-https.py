#!/usr/bin/env python3
"""Unauthenticated, read-only HTTPS ingress acceptance. No SQL or login attempts.

Only run against a change-approved, properly DNS-addressed production host.
"""
import http.client
import ipaddress
import os
import re
import socket
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

def validate_host(host):
    if not isinstance(host,str) or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{1,250})[a-z0-9]",host):
        raise ValueError("Invalid approved production DNS")
    if "." not in host or host.endswith((".invalid",".local",".internal")) or host.replace(".","").isdigit():
        raise ValueError("Production DNS name required, not IP or local hostname")
    return host

def validate_response(path,status,headers):
    hdr={k.lower():str(v) for k,v in headers.items()}
    # All routes have the externally enforced HTTPS security headers.
    if "max-age=31536000" not in hdr.get("strict-transport-security",""):
        raise ValueError("HSTS not enforced")
    if "default-src 'self'" not in hdr.get("content-security-policy",""):
        raise ValueError("Missing *enforced* Content-Security-Policy")
    if hdr.get("x-frame-options","").upper()!="DENY":
        raise ValueError("Frame policy not enforced")
    if hdr.get("x-content-type-options","").lower()!="nosniff":
        raise ValueError("Content sniffing not disabled")
    if path=="/":
        if status!=200:raise ValueError("Web UI not serving 200")
    elif path in ("/api/v1/me","/mcp"):
        if status!=401:raise ValueError("Anonymous private route is not HTTP 401")
        if "no-store" not in hdr.get("cache-control","").lower():
            raise ValueError("Private endpoint permits caching")
    else:
        raise ValueError("Unknown acceptance route")

def fetch(host,path,context):
    url="https://"+host+path
    class DenyRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs):return None
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),
        DenyRedirect(),urllib.request.HTTPSHandler(context=context))
    request=urllib.request.Request(url,headers={"Accept":"text/html,application/json"})
    try:
        with opener.open(request,timeout=10) as result:
            return result.status,dict(result.headers)
    except urllib.error.HTTPError as error:
        return error.code,dict(error.headers)

def main():
    if os.environ.get("DG_PROD_HTTPS_ACCEPTANCE_APPROVED")!="yes":
        raise ValueError("Explicit external production HTTPS acceptance approval required")
    host=validate_host(os.environ["DG_PUBLIC_HOST"])
    resolved=[ipaddress.ip_address(x[4][0]) for x in
        socket.getaddrinfo(host,443,type=socket.SOCK_STREAM)]
    if not resolved or not all(i.is_global for i in resolved):
        raise ValueError("Production hostname resolves to private/ambiguous network")
    context=ssl.create_default_context()
    for path in ("/","/api/v1/me","/mcp"):
        code,headers=fetch(host,path,context)
        validate_response(path,code,headers)
        print("HTTPS_PUBLIC_ACCEPTANCE "+path+" "+str(code)+" PASS")
    print("PRODUCTION_HTTPS_EXTERNAL_ANONYMOUS_SECURITY_PASS")
if __name__=="__main__":
    try:main()
    except (OSError,KeyError,ValueError,TimeoutError,ssl.SSLError,
            urllib.error.URLError) as exc:
        print("PRODUCTION_HTTPS_NO_GO: "+type(exc).__name__,file=sys.stderr)
        sys.exit(1)
