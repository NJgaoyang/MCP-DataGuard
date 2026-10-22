#!/usr/bin/env python3
"""Offline, one-time native admin bootstrap. No web installer or default password.

Requires Linux Python with libxcrypt bcrypt support and PostgreSQL psql client.
Never prints the generated password; writes it to a 0600 local file only.
"""
import argparse
import crypt
import hashlib
import os
import pathlib
import re
import secrets
import struct
import subprocess
import sys
import uuid

ISSUER = "urn:mcp-dataguard:local"
FULL_PERMISSIONS = ('["agent:read","agent:write","datasource:read",'
                    '"datasource:write","identity:admin","agent:identity:write",'
                    '"agent:permission:write","datasource:secret:write","datasource:test"]')

SQL = r"""
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path TO platform;
INSERT INTO dg_organization(id,code,name)
 SELECT :'org_uuid'::uuid,:'org_code',:'org_name'
 WHERE NOT EXISTS(SELECT 1 FROM dg_organization WHERE code=:'org_code');
INSERT INTO dg_role(id,org_id,code,name,system_role,permissions)
 SELECT :'role_uuid'::uuid,o.id,'org-admin','Organization administrators',
  true,CAST(:'permissions' AS jsonb)
 FROM dg_organization o WHERE o.code=:'org_code'
 AND NOT EXISTS(SELECT 1 FROM dg_role r WHERE r.org_id=o.id AND r.code='org-admin');
-- Convert an existing OIDC user only if it has never received a native password.
UPDATE dg_user u SET oidc_issuer=:'issuer',oidc_subject=:'subject',
    oidc_identity_sha256=:'digest',password_hash=:'bcrypt',updated_at=now()
 FROM dg_organization o
 WHERE u.org_id=o.id AND o.code=:'org_code' AND u.username=:'username'
   AND u.password_hash IS NULL;
INSERT INTO dg_user(id,org_id,oidc_issuer,oidc_subject,oidc_identity_sha256,
 username,display_name,status,password_hash)
 SELECT :'user_uuid'::uuid,o.id,:'issuer',:'subject',:'digest',:'username',
  :'display_name','ACTIVE',:'bcrypt'
 FROM dg_organization o WHERE o.code=:'org_code'
 AND NOT EXISTS(SELECT 1 FROM dg_user u WHERE u.org_id=o.id AND u.username=:'username');
INSERT INTO dg_user_role(org_id,user_id,role_id,assigned_by)
 SELECT o.id,u.id,r.id,u.id
 FROM dg_organization o JOIN dg_user u ON u.org_id=o.id
 JOIN dg_role r ON r.org_id=o.id
 WHERE o.code=:'org_code' AND u.username=:'username'
 AND r.code='org-admin' AND r.system_role=true
 ON CONFLICT DO NOTHING;
COMMIT;
"""

def digest(issuer, subject):
    data = b"".join(struct.pack("!I",len(item.encode("utf-8"))) + item.encode("utf-8")
                    for item in (issuer,subject))
    return hashlib.sha256(data).hexdigest()

def psql(statement, variables, tuples=False):
    argv=["psql","-X","-q","-v","ON_ERROR_STOP=1"]
    if tuples: argv.extend(["-t","-A"])
    for key,value in variables.items():
        argv.extend(["-v",f"{key}={value}"])
    finished=subprocess.run(argv,input=statement,encoding="utf-8",capture_output=True,check=False)
    if finished.returncode!=0:
        raise RuntimeError("PostgreSQL bootstrap failed; verify platform role, schema, and account state.")
    return finished.stdout.strip()

def main():
    parser=argparse.ArgumentParser(description="Create the first native MCP DataGuard admin")
    parser.add_argument("--org-code",required=True)
    parser.add_argument("--org-name",required=True)
    parser.add_argument("--username",required=True)
    parser.add_argument("--display-name",required=True)
    parser.add_argument("--password-file",required=True)
    args=parser.parse_args()
    if not re.fullmatch("[a-z][a-z0-9_-]{2,79}",args.org_code):
        parser.error("Invalid organization code")
    if not re.fullmatch("[A-Za-z0-9_.@-]{3,128}",args.username):
        parser.error("Invalid username")
    if not args.org_name or len(args.org_name)>200 or not args.display_name or len(args.display_name)>200:
        parser.error("Invalid display name or organization")
    if os.environ.get("PGUSER") in (None,"postgres","dataguard_bootstrap"):
        parser.error("Use the restricted dg_platform PostgreSQL account via PGUSER")
    if crypt.METHOD_BLOWFISH not in crypt.methods:
        parser.error("libxcrypt bcrypt is required")
    output=pathlib.Path(args.password_file).expanduser().absolute()
    if output.exists():
        parser.error("Password output file exists; choose a new private file")
    preliminary=psql(r"""
      SET search_path TO platform;
      SELECT COALESCE((SELECT (u.password_hash IS NOT NULL)::int FROM dg_user u
       JOIN dg_organization o ON o.id=u.org_id
       WHERE o.code=:'org_code' AND u.username=:'username'),0);
      """,{"org_code":args.org_code,"username":args.username},True)
    if preliminary!="0":
        parser.error("Account already has a native password. Use authenticated password rotation.")
    user_lookup=psql(r"""
      SET search_path TO platform;
      SELECT u.id FROM dg_user u JOIN dg_organization o ON o.id=u.org_id
      WHERE o.code=:'org_code' AND u.username=:'username';
      """,{"org_code":args.org_code,"username":args.username},True)
    user_id=uuid.UUID(user_lookup) if user_lookup else uuid.uuid4()
    subject="local-user:"+str(user_id)
    password=secrets.token_urlsafe(27)
    hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH,rounds=2**12))
    if not hashed or not hashed.startswith(("$2b$12$","$2y$12$")):
        raise RuntimeError("bcrypt hashing failed, refusing bootstrap")
    values=dict(org_uuid=str(uuid.uuid4()),org_code=args.org_code,org_name=args.org_name,
        role_uuid=str(uuid.uuid4()),user_uuid=str(user_id),issuer=ISSUER,
        subject=subject,digest=digest(ISSUER,subject),bcrypt=hashed,
        username=args.username,display_name=args.display_name,permissions=FULL_PERMISSIONS)
    flags=os.O_WRONLY|os.O_CREAT|os.O_EXCL
    fd=os.open(output,flags,0o600)
    try:
        with os.fdopen(fd,"w",encoding="utf-8") as out:
            out.write("Organization: "+args.org_code+"\nUsername: "+args.username+"\nPassword: "+password+"\n")
        psql(SQL,values)
    except Exception:
        output.unlink(missing_ok=True)
        raise
    print("Native administrator created/migrated:",args.org_code,args.username)
    print("Initial password saved privately to:",output)
    print("Change it after logging in. Password was NOT printed.")

if __name__=="__main__":
    try:main()
    except RuntimeError as exc:
        print(str(exc),file=sys.stderr)
        sys.exit(1)
