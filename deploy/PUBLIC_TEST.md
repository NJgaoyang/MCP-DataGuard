# MCP DataGuard: public HTTPS on a test machine

This deployment was tested on a TencentOS 4 test server with Docker 29.7,
one PostgreSQL 17 instance, Flowable 8, Spring Boot 4 and Vue3/Nginx.

**IP address HTTPS is supported** with Caddy's ACME `shortlived` profile.
Certificates are only valid for approximately 6.6 days; leave Caddy running
for automatic renewal. The actual public IP must match the site address and
be reachable from Let's Encrypt on TCP 80/443.

## Files

- `deploy/compose.yaml`: single PostgreSQL 17 database, `platform` and
  `flowable` schemas owned by distinct roles.
- `deploy/compose.dev.yaml`: Spring MCP gateway, optional Flowable, Vue3.
- `deploy/compose.identity.yaml`: **test-only** Keycloak dev-mode identity service.
- `deploy/compose.public.yaml`: Caddy HTTPS ingress.
- `deploy/.env.dev`, `deploy/.env.idp`, `deploy/.env.public`: private local config,
  created from examples, never committed.

## Test machine bootstrap

1. Generate unique random passwords separately for the PG bootstrap, platform,
   Flowable and Keycloak admin accounts. Protect files with `chmod 600`.
2. In `.env.dev`, configure `OIDC_ISSUER=https://IP/identity/realms/dataguard`,
   `VITE_OIDC_AUTHORITY` to the same, `VITE_OIDC_REDIRECT_URI=https://IP/auth/callback`,
   `OIDC_AUDIENCE=mcp-dataguard`, and for the self-hosted test IdP
   `OIDC_JWKS_URI=http://keycloak:8080/identity/realms/dataguard/protocol/openid-connect/certs`.
3. In `.env.public`, set `DG_PUBLIC_HOST=IP`,
   `DG_PUBLIC_URL=https://IP/identity`.
4. Initialize Keycloak realm `dataguard`, public client `dataguard-web` with
   PKCE S256 and exact redirect URI, and add a dedicated audience mapper for
   `mcp-dataguard` access tokens. Create a test user and set its password.
5. Provision its identity as a platform user through `bin/bootstrap-admin.py`
   (see `docs/BOOTSTRAP_ADMIN.md`).
6. Start the services:

```bash
docker compose --env-file deploy/.env.dev --env-file deploy/.env.idp \
  --env-file deploy/.env.public \
  -f deploy/compose.yaml -f deploy/compose.dev.yaml \
  -f deploy/compose.identity.yaml -f deploy/compose.public.yaml \
  up -d --build postgres keycloak gateway web public

# Optional Flowable test engine: only on a fresh disposable database
docker compose --env-file deploy/.env.dev --env-file deploy/.env.idp \
  --env-file deploy/.env.public \
  -f deploy/compose.yaml -f deploy/compose.dev.yaml \
  -f deploy/compose.identity.yaml -f deploy/compose.public.yaml \
  --profile workflow up -d --build workflow
```

Verify `https://IP/` HTTP 200, `https://IP/api/v1/me` HTTP 401 without
a bearer token, and `https://IP/identity/realms/dataguard/.well-known/openid-configuration`
HTTP 200. Do not confuse this with a complete approval/write SQL gateway.

**Security:** This is development-only. The Keycloak `start-dev` database
and demo accounts are NOT production-grade. No database port or raw JVM service
should be published to the Internet. Never reuse the host root password.
