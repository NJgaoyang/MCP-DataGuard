#!/usr/bin/env python3
"""Isolated HTTP login / first password change acceptance. Never use against production."""
import http.cookiejar
import json
import os
import urllib.parse
import urllib.request
import urllib.error
import sys
BASE=os.environ.get("DG_STANDALONE_TEST_URL","http://127.0.0.1:8080")
if not BASE.startswith(("http://127.0.0.1:","http://localhost:")):
    raise SystemExit("Only loopback CI target accepted")
cookies=http.cookiejar.CookieJar()
opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
class StopRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None
opener.add_handler(StopRedirect())
def call(path,method="GET",payload=None,csrf=None):
    headers={}
    if csrf:headers["X-CSRF-TOKEN"]=csrf
    if payload is not None:
        headers["Content-Type"]="application/json"
        payload=json.dumps(payload).encode()
    req=urllib.request.Request(BASE+path,data=payload,method=method,headers=headers)
    try:
        with opener.open(req,timeout=10) as r:
            return r.status,r.read(),dict(r.headers)
    except urllib.error.HTTPError as r:
        return r.code,r.read(),dict(r.headers)
def token():
    code,body,_=call("/api/csrf")
    assert code==200,(code,body[:150])
    return json.loads(body)["token"]
csrf=token()
form=urllib.parse.urlencode({"username":"admin","password":"123456","_csrf":csrf}).encode()
request=urllib.request.Request(BASE+"/auth/login",data=form,method="POST",headers={"Content-Type":"application/x-www-form-urlencoded"})
try:
    with opener.open(request,timeout=10):pass
except urllib.error.HTTPError as e:
    assert e.code in (302,303),(e.code,e.read()[:100])
status,body,_=call("/api/me")
assert status==200,(status,body[:100])
user=json.loads(body)
assert user["username"]=="admin" and user["mustChangePassword"] in (1,True),user
status,_,_=call("/api/sources")
assert status==428,status
csrf=token()
password=os.environ.get("DG_MONOLITH_TEST_NEW_PASSWORD","ChangeMe_2026!NoReuse")
status,body,_=call("/api/me/password","POST",{"currentPassword":"123456","newPassword":password},csrf)
assert status==200,(status,body[:160])
status,_,_=call("/api/me")
assert status in (401,403),status
print("MY_SQL_MONOLITH_LOGIN_FORCED_CHANGE_AND_SESSION_REVOKE_PASS")