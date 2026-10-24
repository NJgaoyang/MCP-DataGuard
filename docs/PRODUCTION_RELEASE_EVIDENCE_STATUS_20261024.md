# Release completeness status — verified facts / external dependencies

As of 2026-10-24: code hardening is under PR #2, **not yet merged to dev20261022**. No branch protection, real three-signer approval, certified production DB or live production notification delivery has been proven. Gate remains **NO-GO**.

## Hardening added in this change

- Standalone `deploy/compose.production.yaml` without database container; immutable image digests for all four services; only Caddy public ports, private JDBC services and HTTPS `verify-full` with explicit mounted CA.
- Root-owned group-10001 (0640) source secret files mounted as Docker file secrets; runtime JVM starts as UID/GID 10001. Secrets do not appear in Docker `Config.Env` but remain visible to the JVM/process with sufficient host privilege. External organization secrets lifecycle still required.
- Caddy production enforced CSP and HTTPS ACME for a real DNS hostname, separate from public IP test overlay. Verify application front-end compatibility, browsers, TLS trust and renewal in a real preproduction environment.
- Negative standalone Compose policy tests; read-only deploy preflight checks evidence directory, signed GPG approval, exact immutable image alignment, secure stage flags and clean checkout.
- Read-only runtime image, hardening and approved-stage verification to prevent unauthorized container substitution.
- All runtime write flags remain OFF by default; this work **does not deploy any production application**.

## Not yet externally certifiable

Real Flowable operators, final PostgreSQL datasource grants + trusted cert, Java Worker chaos in target preproduction, two-person UNKNOWN reconciliation, live pager delivery, independent immutable audit storage, production GPG and credential custody, SBOM/vulnerability/pen-test acceptance, signed production change and rollback. See `docs/PRODUCTION_ACCEPTANCE_EXECUTION.md` and issue #3.

**No-go until every named record in the release readiness checklist is independently substantiated and reviewed.**
