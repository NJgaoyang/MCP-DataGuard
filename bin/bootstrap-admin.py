#!/usr/bin/env python3
"""Offline, explicit first-organization bootstrap for the platform schema.

Run with the *platform* database role after Flyway migrations, with a local
OIDC provider configured. Never enable an unauthenticated web bootstrap API.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import struct
import subprocess
import sys
import uuid

CODE_RE = re.compile(r"^[a-z][a-z0-9_-]{2,79}$")
USERNAME_RE = re.compile(r"^[A-Za-z0-9_.@-]{3,128}$")

SQL = r"""
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path TO platform;
INSERT INTO dg_organization(id,code,name) VALUES (:'org_uuid'::uuid,:'org_code',:'org_name');
INSERT INTO dg_user(id,org_id,oidc_issuer,oidc_subject,oidc_identity_sha256,
                    username,display_name,status)
VALUES (:'user_uuid'::uuid,:'org_uuid'::uuid,:'issuer',:'subject',:'identity_digest',
        :'username',:'display_name','ACTIVE');
INSERT INTO dg_role(id,org_id,code,name,permissions,system_role)
VALUES (:'role_uuid'::uuid,:'org_uuid'::uuid,'org-admin','Organization administrators',
        '["agent:read","agent:write","datasource:read","datasource:write"]'::jsonb,true);
INSERT INTO dg_user_role(org_id,user_id,role_id)
VALUES (:'org_uuid'::uuid,:'user_uuid'::uuid,:'role_uuid'::uuid);
INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
VALUES (:'event_uuid'::uuid,:'org_uuid'::uuid,:'user_uuid',
        'BOOTSTRAP_ORGANIZATION','ORGANIZATION',:'org_uuid');
COMMIT;
"""

CHECK_IDENTITY = r"""
SET search_path TO platform;
SELECT count(*) FROM dg_user WHERE oidc_identity_sha256=:'identity_digest';
"""


def oidc_digest(issuer: str, subject: str) -> str:
    data = b""
    for text in (issuer, subject):
        encoded = text.encode("utf-8")
        data += struct.pack("!I", len(encoded)) + encoded
    return hashlib.sha256(data).hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Initialize an organization and its first OIDC admin")
    parser.add_argument("--org-code", required=True, help="Unique lowercase organization code")
    parser.add_argument("--org-name", required=True)
    parser.add_argument("--issuer", required=True, help="Exact trusted OIDC iss claim")
    parser.add_argument("--subject", required=True, help="Exact administrator OIDC sub claim")
    parser.add_argument("--username", required=True)
    parser.add_argument("--display-name", required=True)
    return parser.parse_args()


def checked(args: argparse.Namespace) -> None:
    if not CODE_RE.fullmatch(args.org_code):
        raise ValueError("org-code must be lowercase ASCII, 3-80 chars")
    if not USERNAME_RE.fullmatch(args.username):
        raise ValueError("username must be 3-128 plain ASCII chars")
    for name, limit in (("org_name", 200), ("issuer", 512), ("subject", 512), ("display_name", 200)):
        value = getattr(args, name)
        if not value or not value.strip() or len(value) > limit or "\x00" in value:
            raise ValueError(f"Invalid {name}")
    if not os.getenv("PGHOST") or not os.getenv("PGUSER"):
        raise ValueError("Set PGHOST and PGUSER for the platform management database")
    if os.getenv("PGUSER") in {"postgres", "dataguard_bootstrap"}:
        raise ValueError("Use the limited platform role (dg_platform), not a superuser")


def run_sql(statement: str, values: dict[str, str], read_only: bool = False) -> str:
    command = ["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", os.getenv("PGDATABASE", "dataguard")]
    if read_only:
        command += ["-t", "-A"]
    for name, value in values.items():
        command += ["-v", f"{name}={value}"]
    completed = subprocess.run(command, input=statement, text=True, capture_output=True, check=False)
    if completed.returncode:
        # No psql command-line values or database credentials are printed.
        raise RuntimeError("PostgreSQL operation failed; check schema, permissions, and uniqueness constraints")
    return completed.stdout.strip()


def main() -> int:
    args = parse_args()
    try:
        checked(args)
        digest = oidc_digest(args.issuer, args.subject)
        identity_count = int(run_sql(CHECK_IDENTITY, {"identity_digest": digest}, read_only=True))
        if identity_count:
            raise ValueError("OIDC principal is already registered: use an organization membership workflow")
        values = {
            "org_uuid": str(uuid.uuid4()), "user_uuid": str(uuid.uuid4()),
            "role_uuid": str(uuid.uuid4()), "event_uuid": str(uuid.uuid4()),
            "org_code": args.org_code, "org_name": args.org_name,
            "issuer": args.issuer, "subject": args.subject,
            "username": args.username, "display_name": args.display_name,
            "identity_digest": digest,
        }
        run_sql(SQL, values)
        print("Created organization:", args.org_code)
        print("Created OIDC administrator:", args.username)
        print("Only Agent/datasource management permissions assigned. No SQL execution permission granted.")
        return 0
    except (ValueError, RuntimeError) as exc:
        print("Initialization rejected:", str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
