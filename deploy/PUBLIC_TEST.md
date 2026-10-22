# New test machine — HTTPS public access

The optional public ingress publishes **only TCP 80/443**, using Caddy as an
HTTPS reverse proxy to the existing Nginx/Vue console and authenticated gateway.
PostgreSQL remains bound to 127.0.0.1 and the JVM ports have no host mappings.

**Prerequisites:** the newly connected Remote Desktop Commander device must
actually be Online; a DNS name pointing to its current IP; TCP 80/443 open in
its security group; and a real OIDC provider with a public PKCE browser client.

1. Follow [development deployment](DEPLOY_DEV.md) to prepare deploy/.env.dev.
2. Copy deploy/.env.public.example to deploy/.env.public and set the actual
   domain plus the ACME account email; protect both files with chmod 600.
3. In deploy/.env.dev set the OIDC issuer/authority/client and set the browser
   redirect URI to https://YOUR_DOMAIN/auth/callback. Register that redirect
   in the IdP.
4. Run bash bin/start-public-test.sh on the **new** server.

Caddy obtains certificates when public DNS and ports are correctly configured.
Do not assume the old development machine's IP belongs to this server.

Verify https://YOUR_DOMAIN/ and check that a request to
https://YOUR_DOMAIN/api/v1/me without an access token returns HTTP 401.

This stack is only a development/test deployment: SQL execution and the
Flowable-to-write-executor approval chain are not production ready. A publicly
reachable development server is still exposed to scanners and brute-force
traffic, so leave authentication enabled and do not open database ports.
