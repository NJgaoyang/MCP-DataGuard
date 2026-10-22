# MCP DataGuard — native authentication

**Default mode:** no Keycloak, no OIDC issuer, no remote JWKS. The PostgreSQL-backed
gateway authenticates administrators with BCrypt password hashes and agents
with separately issued random opaque bearer tokens.

## Login & authorization

The public login endpoint is `POST /api/v1/auth/login`:

```json
{"organization":"dataguard","username":"dgadmin","password":"<your-private-password>"}
```

Successful responses contain an ephemeral 256-bit bearer token. The Web admin
keeps it **in memory only**; refreshing requires signing in again. Do not
place it in URL parameters, browser local storage, logs or database tables.

- Native session TTL: 8 hours. Agent token TTL: 90 days.
- Only SHA-256 **token digests** live in `dg_native_session` and
  `dg_native_agent_token`; token plaintext is only shown once.
- Wrong password attempts are counted per account, locked for 15 minutes
  after 5 failures; basic in-memory rate limit also applies.
- `POST /api/v1/auth/logout` invalidates the bearer; password change at
  `POST /api/v1/auth/password` revokes all sessions.
- Role grants continue to use server-side `dg_user_role` and `dg_role`.
- Agent tokens remain bound to `dg_agent` and `dg_agent_permission`. SQL
  execution remains **denied** until its separate safety review.

## Bootstrap first admin (offline only)

The service never creates a default admin password. After Flyway V3 has
completed, connect to the target host console as a trusted operator and set
PostgreSQL access to the limited `dg_platform` role:

```bash
export PGHOST=127.0.0.1 PGPORT=55432 PGDATABASE=dataguard PGUSER=dg_platform
# Supply PGPASSWORD securely from the private deploy/.env.dev (never in Git).
python3 bin/bootstrap-native-admin.py \
  --org-code dataguard --org-name "MCP DataGuard" \
  --username dgadmin --display-name Administrator \
  --password-file /root/mcp-dataguard-native-login.txt
```

The command can safely migrate a previous account **without a native password**
to native local identity, or create an entirely new organization and its first
administrator on an empty platform database. The generated strong initial
password is written to the selected file with 0600 permissions and **never
printed or committed**. An account with an existing native password cannot
be reset with this bootstrap command. Change the initial password in the UI
after login.

On TencentOS Server 4, Python 3.11's `crypt` backed by libxcrypt is used for
BCrypt $2b$12 hashing. Python 3.13 may not provide `crypt`; use a
supported BCrypt tool or future cross-platform provisioner instead.

## Deployment

The native gateway, PostgreSQL, optional Flowable and Nginx/Vue are enough:

```bash
docker compose --env-file deploy/.env.dev \
  -f deploy/compose.yaml -f deploy/compose.dev.yaml \
  --profile workflow up -d --build postgres gateway web workflow
```

For public test HTTPS, include `deploy/.env.public` and
`-f deploy/compose.public.yaml` with the same command and add `public` to
service names. Keep database and JVM ports internal.

No Keycloak or external IdP starts in the native configuration. Existing
Keycloak data volumes can be retained for recovery; do not delete them
as part of authentication migration without backup.

## Security and limitations

The internal session tokens are **not JWTs**. The authentication filter adapts
database-authenticated opaque sessions into the Spring Security principal
shape used by existing permission resolvers. It never accepts claims supplied
by the client. Login is HTTPS-only for any public deployment.

Keycloak/OIDC integration is postponed. Legacy OIDC identities will not log in
until migrated to native accounts. The existing SQL firewall, physical read
isolation, approval/writer execution and full audit chain are **not finished**;
native authentication does not unlock SQL execution.
