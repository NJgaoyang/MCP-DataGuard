# MCP DataGuard — isolated development deployment

Development only: MCP SQL execution is denied by default. Flowable approvals are not connected to a write executor.

## Requirements

Linux host with Docker Engine, Docker Compose V2, Git, outbound image/npm/Maven registry access, and a real OIDC provider registered for browser Authorization Code + PKCE.

## Install on an authorized development host

```bash
git clone --branch dev20261022 https://github.com/NJgaoyang/MCP-DataGuard.git
cd MCP-DataGuard
cp deploy/.env.dev.example deploy/.env.dev
chmod 600 deploy/.env.dev
# Edit strong database passwords and actual OIDC provider/client/redirect settings
bash bin/check-dev-deploy.sh
bash bin/start-dev-deploy.sh
```

Only one PostgreSQL 17 database (dataguard) is used, with isolated platform and flowable schemas. The console and database are bound to localhost, not the public network.

Workstation browser access via SSH tunnel:

```bash
ssh -L 18080:127.0.0.1:18080 <operator>@<server>
```

Open http://127.0.0.1:18080 and register http://127.0.0.1:18080/auth/callback in the OIDC provider (or change the values consistently).

The real issuer must be reachable from the gateway container. Without a registered OIDC identity the console will not authorize users; see docs/BOOTSTRAP_ADMIN.md for the offline initial organization/admin setup.

## Optional Flowable development profile

Flowable runtime table initialization must only be tested on a fresh disposable database:

```bash
docker compose --env-file deploy/.env.dev -f deploy/compose.yaml \
  -f deploy/compose.dev.yaml --profile workflow up -d --build workflow
```

This does NOT implement SQL approval workflow processing yet.

## Verify

```bash
docker compose --env-file deploy/.env.dev -f deploy/compose.yaml -f deploy/compose.dev.yaml ps
curl -I http://127.0.0.1:18080/
curl -i http://127.0.0.1:18080/api/v1/me
```

Once the gateway is running, unauthenticated /api/v1/me must return HTTP 401.

Do not expose HTTP directly to the public Internet or reuse host root passwords as application credentials. This is not a hardened production deployment. Real server build, networking, OIDC integration and Flowable compatibility must be verified.
