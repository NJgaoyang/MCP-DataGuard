# Isolated release acceptance — 2026-10-23

Target: `dev20261022` commit `28504a41b65689601184ac62fdda58d00c3dd9b1`.
Rollback candidate: `d64d92807292f0518f98315c4a3f0ef953500aa5`.

## Completed on independent development instance

- Clean Git clone; `bin/preflight-release.sh` PASS with write, read and workflow consumer flags disabled.
- Docker Maven reactor verify PASS; Vue typecheck/Vite production build PASS.
- Latest Spring Boot repackaged JAR started against **empty isolated PostgreSQL 17**, all Flyway V1–V3 migrations applied.
- Spring Boot registered 6 MCP tools.
- Independent static Web served index and JS assets via private-container loopback.
- `NativeHttpSmokeTest` against the actual running backend, Web and PostgreSQL passed (1/1, 0 failure):
  - login; forced password change; old password/session invalid;
  - new session read of profile, approvals, security alerts, audit integrity and operations;
  - anonymous MCP/profile denied and Web bundle served.
- Restarted latest gateway **without recreating DB**. Flyway schema history stayed v3, health HTTP 200, anonymous profile HTTP 401.
- Stopped latest gateway, started rollback candidate `d64d9280` against **same isolated PostgreSQL**. Health HTTP 200, anonymous profile HTTP 401.
- Stopped rollback candidate, started latest gateway again with the same DB. Health HTTP 200, anonymous MCP HTTP 401, Web HTTP 200.
- Re-ran real `NativeHttpSmokeTest` on restored latest gateway: PASS (1/1).

Prior same-branch separate verification: `bin/verify-isolated-postgres.sh` with outbox/readonly-TLS/write-TLS flags passed, including real PostgreSQL JDBC update and UNKNOWN no-replay checks.

## Not certified

- No production deployment or change to existing `deploy-*` services.
- No browser-driven click path (HTTP static-resource check is not a browser UI test).
- No live external MCP client handshake; only HTTP endpoint auth negative test.
- No controlled packet-loss/kill-at-remote-commit chaos scenario for distributed write reconciliation.
- No production enabling of WRITE, READ or dynamic workflow execution.
- Old and new commits use the same Flyway schema baseline in this acceptance; this does not guarantee arbitrary future migration rollback.

## Release gate

All SQL physical writes remain **disabled by default**. A distinct security release approval
and target DB least-privilege/TLS/backup validation are required before enabling write execution.
