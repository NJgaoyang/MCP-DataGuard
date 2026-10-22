# MCP DataGuard — native authentication public HTTPS deployment

The default deployment consists of PostgreSQL 17, Java Spring Boot gateway,
Vue3/Nginx frontend and Caddy HTTPS. Flowable is optional.
**No Keycloak, OIDC provider, or external identity system is needed.**

For a public IPv4 test, set DG_PUBLIC_HOST to your host IP and keep ports
80/443 reachable so Caddy's short-lived Let's Encrypt certificates renew.

Create private files based on `deploy/.env.dev.example` and
`deploy/.env.public.example` (chmod 600). Then:

```bash
bash bin/start-public-test.sh
```

The gateway applies Flyway V1-V3. Next, initialize the first local administrator
on the server console via `bin/bootstrap-native-admin.py`.
Detailed instructions: `docs/NATIVE_AUTH.md`.

The bootstrap command consumes a **root-only temporary password file**,
does not print credentials, and refuses to reset accounts with existing
native passwords. The browser enforces password rotation on the first login,
and the backend prevents business API access before rotation.

The admin and Agent credentials use separate revocable opaque bearer tokens.
Do not log or send them over non-HTTPS links. PostgreSQL and JVM ports must
not be reachable from the public internet.

SQL execution remains disabled independently of authentication.
