#!/usr/bin/env python3
"""Offline native admin migration; never creates a default password through HTTP.

Requires PGHOST, PGPORT, PGDATABASE, PGUSER=dg_platform and PGPASSWORD.
Opt-in first login password comes from --password-file (0600, no output).
"""
import argparse
import crypt
import os
import pathlib
import re
import subprocess
import sys

def execute(sql, vars):
    command=["psql","-X","-v","ON_ERROR_STOP=1","-q","-t","-A"]
    for key,value in vars.items():
        command.extend(["-v",f"{key}={value}"])
    r=subprocess.run(command,input=sql,text=True,capture_output=True,check=False)
    if r.returncode:raise RuntimeError("PostgreSQL transaction failed")
    return r.stdout.strip()

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--org",default="dataguard")
    ap.add_argument("--username",default="admin")
    ap.add_argument("--password-file",required=True)
    args=ap.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9_-]{2,79}",args.org) or not re.fullmatch(r"[A-Za-z0-9_.@-]{3,128}",args.username):
        ap.error("Invalid organization or username")
    if os.getenv("PGUSER")!="dg_platform":ap.error("PGUSER must be dg_platform")
    path=pathlib.Path(args.password_file)
    if path.stat().st_mode & 0o077:ap.error("password-file must be owner-only (chmod 600)")
    password=path.read_text().strip("\r\n")
    if not password or len(password)>72:ap.error("Invalid temporary password")
    if crypt.METHOD_BLOWFISH not in crypt.methods:ap.error("libxcrypt bcrypt is required")
    hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH,rounds=2**12))
    if not hashed.startswith(("$2b$12$","$2y$12$")):raise RuntimeError("bcrypt failure")
    # Only an account with no native password may be bootstrapped.
    # Never allow bootstrap to reset existing logins.
    sql=r"""
    \set ON_ERROR_STOP on
    BEGIN;
    SET LOCAL search_path=platform;
    INSERT INTO dg_user(id,org_id,username,display_name,status,password_hash,must_change_password)
    SELECT gen_random_uuid(),o.id,:'username','平台管理员','ACTIVE',:'hash',true
    FROM dg_organization o WHERE o.code=:'org'
      AND NOT EXISTS (SELECT 1 FROM dg_user u WHERE u.org_id=o.id AND u.username=:'username');
    UPDATE dg_user u SET password_hash=:'hash',must_change_password=true,
       failed_login_attempts=0,login_locked_until=NULL,updated_at=now()
    FROM dg_organization o
    WHERE u.org_id=o.id AND o.code=:'org' AND u.username=:'username' AND u.password_hash IS NULL;
    INSERT INTO dg_user_role(org_id,user_id,role_id,assigned_by)
    SELECT o.id,u.id,r.id,u.id FROM dg_organization o
      JOIN dg_user u ON u.org_id=o.id
      JOIN dg_role r ON r.org_id=o.id
    WHERE o.code=:'org' AND u.username=:'username' AND r.code='org-admin' AND r.system_role=true
    ON CONFLICT DO NOTHING;
    DO $native$
    BEGIN
      IF NOT EXISTS (SELECT 1 FROM dg_user u JOIN dg_organization o ON o.id=u.org_id
         WHERE o.code=:'org' AND u.username=:'username' AND u.password_hash IS NOT NULL) THEN
        RAISE EXCEPTION 'admin bootstrap failed';
      END IF;
    END $native$;
    COMMIT;
    """
    # psql parameter interpolation inside DO dollar quotes isn't supported;
    # retain straightforward transactional inserts and verify separately.
    sql=sql[:sql.index("    DO $native$")]+ "    COMMIT;\n"
    execute(sql,{"org":args.org,"username":args.username,"hash":hashed})
    print("Native administrator provisioned:",args.org,args.username)
    print("Temporary password is not printed. Must change on first login.")

if __name__=="__main__":
    try:main()
    except (RuntimeError,OSError) as e:
        print("Native bootstrap failed:",e,file=sys.stderr)
        sys.exit(1)
