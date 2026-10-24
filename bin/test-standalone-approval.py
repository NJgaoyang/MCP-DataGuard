#!/usr/bin/env python3
"""End-to-end MySQL + HTTP + embedded Flowable, disposable CI target ONLY."""
import http.cookiejar
import json
import os
import urllib.parse
import urllib.request
import urllib.error

URL=os.environ.get("DG_STANDALONE_TEST_URL","http://127.0.0.1:8080")
if not URL.startswith(("http://127.0.0.1:","http://localhost:")):
    raise SystemExit("Refuse any non-loopback acceptance target")

class Actor:
    def __init__(self):
        self.cookie=http.cookiejar.CookieJar()
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookie))
    def call(self,path,method="GET",data=None,headers=None):
        payload=None
        h=headers or {}
        if data is not None:
            payload=json.dumps(data).encode()
            h["Content-Type"]="application/json"
        req=urllib.request.Request(URL+path,data=payload,method=method,headers=h)
        try:
            with self.opener.open(req,timeout=15) as r:return r.status,r.read()
        except urllib.error.HTTPError as e:return e.code,e.read()
    def csrf(self):
        code,body=self.call("/api/csrf")
        assert code==200,(code,body[:120])
        return json.loads(body)["token"]
    def login(self,username,password):
        tok=self.csrf()
        payload=urllib.parse.urlencode({"username":username,"password":password,"_csrf":tok}).encode()
        req=urllib.request.Request(URL+"/auth/login",data=payload,
                  headers={"Content-Type":"application/x-www-form-urlencoded"})
        with self.opener.open(req,timeout=15) as r:r.read(500)
        code,body=self.call("/api/me")
        assert code==200,(username,code,body[:150])
        return json.loads(body)
    def post(self,path,payload):
        return self.call(path,"POST",payload,{"X-CSRF-TOKEN":self.csrf()})
    def check(self,path,method="GET",data=None,expected=200):
        code,body=(self.call(path) if method=="GET" else self.post(path,data))
        assert code==expected,(path,code,body[:240])
        if code!=200:return None
        return json.loads(body)

admin=Actor()
me=admin.login("admin",os.environ.get("DG_MONOLITH_TEST_NEW_PASSWORD","ChangeMe_2026!NoReuse"))
assert me["mustChangePassword"] in (False,0),me
other=admin.check("/api/users",expected=200)
assert len(other)==1,other

created=admin.check("/api/users","POST",{"username":"testreviewer",
    "displayName":"测试审批人","roleCode":"APPROVER",
    "temporaryPassword":"Temporary_2026!Reviewed"})
reviewer_id=created["id"]
approvers=admin.check("/api/users/approvers")
assert all(r["id"]!=reviewer_id for r in approvers),"Unchanged temporary password reviewer must be excluded"

src=admin.check("/api/sources","POST",{"code":"test_mysql",
    "displayName":"CI Test MySQL","host":"localhost","port":3306,
    "databaseName":"dataguard","readUsername":"reader",
    "passwordEnv":"DATAGUARD_TARGET_CI_TEST_PASSWORD"})
source_id=src["id"]
assert src["enabled"] in (False,0)
allowed=admin.check("/api/sources/"+source_id+"/allowlist","POST",
    {"tableName":"ci_allowlisted_table"})
assert allowed["allowed"]
admin.check("/api/sources/"+source_id+"/enable","POST",{})
bad_code,_=admin.post("/api/approvals",{"sourceId":source_id,
    "tableName":"never_allowlisted","approverId":reviewer_id,
    "reason":"Must be refused"})
assert bad_code==403,bad_code

reviewer=Actor()
first=reviewer.login("testreviewer","Temporary_2026!Reviewed")
assert first["mustChangePassword"] in (1,True)
code,_=reviewer.call("/api/approvals/my")
assert code==428,code
changed=reviewer.check("/api/me/password","POST",{
    "currentPassword":"Temporary_2026!Reviewed",
    "newPassword":"Changed_2026!Reviewed"})
assert changed["reauthenticate"] is True
code,_=reviewer.call("/api/me")
assert code==401,code
reviewer=Actor()
second=reviewer.login("testreviewer","Changed_2026!Reviewed")
assert second["mustChangePassword"] in (False,0)
listed=admin.check("/api/users/approvers")
assert any(x["id"]==reviewer_id for x in listed)

ticket=admin.check("/api/approvals","POST",{
    "sourceId":source_id,"tableName":"ci_allowlisted_table",
    "approverId":reviewer_id,"reason":"Business test for approved access"})
assert ticket["status"]=="PENDING"
request_id=ticket["id"]
code,_=admin.post("/api/approvals/"+request_id+"/approve",{"reason":"Self review prohibited"})
assert code in (403,409),code
my=reviewer.check("/api/approvals/my")
assert len(my)==1 and my[0]["id"]==request_id,my
approved=reviewer.check("/api/approvals/"+request_id+"/approve","POST",
    {"reason":"Reviewed by internal account"})
assert approved["status"]=="APPROVED",approved
assert reviewer.check("/api/approvals/my")==[]

audit=admin.check("/api/audit")
actions=[row["action"] for row in audit]
for wanted in ("USER_CREATED","TABLE_ALLOWED","APPROVAL_REQUESTED",
               "APPROVAL_APPROVED","PASSWORD_CHANGED"):
    assert wanted in actions,(wanted,actions)
print("MYSQL_INTERNAL_USER_FLOWABLE_ALLOWLIST_AUDIT_E2E_PASS")
