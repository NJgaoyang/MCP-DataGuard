# Native authentication migration (no Keycloak)

MCP DataGuard now uses its own PostgreSQL-backed user sessions and Agent tokens.
The web app signs in directly through `POST /api/v1/auth/login`.
No OIDC authority, redirect URI, or Keycloak container is required in the
native deployment.

## First login

An administrator supplies a private temporary-password file on the server,
then runs the **offline** `bin/bootstrap-native-admin.py` tool under the
`dg_platform` PostgreSQL role. The file must be owner-only (`chmod 600`).
No default password is baked into the application, container, or Git.

The script can enroll the existing `admin` account with no native password
and attach its existing `org-admin` role. It will not overwrite an existing
native password. After first login, the backend permits only
`POST /api/v1/auth/password` and `POST /api/v1/auth/logout` until the
temporary password is replaced by a 12+ character complex password.
All existing sessions are invalidated at password change.

## API lifecycle

`POST /api/v1/auth/login`: input
`{"organization":"dataguard","username":"admin","password":"..."}`.
Returns an **opaque** 256-bit bearer token and `mustChangePassword`.
The frontend keeps it only in memory and never saves it in localStorage.

`POST /api/v1/auth/password`: body `currentPassword` and `newPassword`.
`POST /api/v1/auth/logout`: revoke current token.
Org administrators create further accounts with a strong initial password
using `POST /api/v1/admin/users`; those accounts must change it at first login.

`POST /api/v1/agents/{agentId}/native-token`: mint a 30-day random agent
bearer (shown once; issuing again revokes existing tokens).
`POST /api/v1/agents/{agentId}/native-token/revoke`: revoke.
SQL execution is still disabled independently from token authentication.

## Deployment precautions

Apply Flyway V3 before provisioning accounts.
Verify login, forced password change, logout and Agent token authorization
before stopping the old Keycloak container and removing OIDC environment files.
Retain a rollback backup of the PostgreSQL platform schema.

**Security follow-ups:** implement rate limiting outside PostgreSQL row locks,
audit login failures without revealing secrets, restrict token creation to
fine-grained agent permissions, and implement secured HttpOnly cookie + CSRF
when persistent browser sessions are desired.
