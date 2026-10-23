# Native authentication HTTP acceptance

This is an opt-in **real HTTP** test, separate from CI unit tests.

## Coverage

`NativeHttpSmokeTest` provisions a unique temporary admin user in an isolated
PostgreSQL database and verifies:

1. Health returns 200; anonymous `/api/v1/me` and `/mcp` return 401.
2. First login succeeds with `mustChangePassword=true`.
3. First-login bearer rotates the password through `/api/v1/auth/password`.
4. Old password and old bearer no longer work.
5. New login returns `mustChangePassword=false`; new bearer reaches `/api/v1/me`.
6. Operations summary, workflow dead-letter, security-alert and audit-integrity endpoints return 200.
7. The independent frontend serves index and its JavaScript bundle.

## Isolated execution

Use an **empty** PostgreSQL 17 database; let the Spring Boot application run
Flyway itself. Do not manually apply V1-V3 SQL and then start Flyway against that
same schema without its history table.

Do not publish ports or reuse production credentials. Run the application and
frontend only in an isolated test network, and run the test container within
that same network. The server and frontend URLs must resolve to actual running
servers; using the backend URL for both will cause a false failure.

```bash
DG_HTTP_E2E_URL=http://127.0.0.1:8080 \
DG_WEB_E2E_URL=http://127.0.0.1:8081 \
DG_DB_E2E_URL='jdbc:postgresql://127.0.0.1:5432/dataguard?currentSchema=platform' \
mvn -B -ntp -pl dataguard-server -am \
  -Dtest=NativeHttpSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The connection URL is only for a disposable, local-network test DB. The
test generates a different organization and passwords on each run. Repeated
runs do not replace an existing admin.

## Acceptance evidence

On 2026-10-23 this test passed against an isolated PostgreSQL 17 database,
Spring Boot backend and separate Java HTTP static frontend:

`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS`.

This is not evidence of a production deployment or a browser UI journey.
