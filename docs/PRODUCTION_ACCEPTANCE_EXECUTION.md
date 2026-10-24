# MCP DataGuard release qualification and controlled rollout

Status: **NO-GO** until independently collected production evidence and signed release approval.
Scope: `dev20261022` and release candidates, PostgreSQL first. Not a report of a completed production deployment.
Offsite backups are explicitly excluded by business decision; residual whole-host-loss risk needs signed acceptance.

## 1. What code automation can demonstrate

- The mandatory `.github/workflows/mandatory-security-gates.yml` runs on **every PR targeting `dev20261022`** and push to that branch; it does not use a path filter.
- Its `isolated-security` job runs `verify-core-security-gates.sh` against **disposable** PostgreSQL, real MCP HTTP, Flowable Outbox, read/TLS, write/TLS, real network COMMIT failure and actual JVM SIGKILL; XML reports must have **no skipped critical integration suite**. A green `mvn verify` alone is not sufficient.
- `release-hardening` checks hardened Compose, notification policy, local backup policy, signed-release validation and negative stage policy. A missing evidence directory must result in NO-GO.
- The signed production manifest verifies evidence field presence and three distinct GPG signatures. It **does not authenticate the contents of evidence**. Signers independently inspect originals, identity, target data sources, time bounds and deployment artifacts.

## 2. Required repository governance (repository administrator)

The GitHub integration currently permits code/PR writes, **not branch protection/ruleset configuration**. The administrator must configure:
1. Protect `dev20261022` and `main`; forbid force pushes and direct writes except approved break-glass access.
2. Require at least one independent PR approval, stale approval dismissal and conversation resolution.
3. Require check contexts `isolated-security`, `release-hardening`, plus all existing `Backend and contract checks` jobs and `Browser acceptance` when front-end paths change. These are concrete GitHub Actions job names, not a mere green workflow badge.
4. Require up-to-date branches / merge queue as supported by the organization and restrict workflow-file changes.
5. Use signed, SHA-pinned image digests and a release change record; **never** publish using floating `latest`.
6. Verify these settings through the GitHub API before changing status to GO.

## 3. Pre-production business acceptance (real infrastructure, named witnesses)

### Identity and Flowable
In an isolated pre-production deployment with dedicated users: applicant/Agent owner, `db-owner`, `dba`, outsider and two reconciliation operators. Use **real HTTPS browser/API sessions** and **running Workflow service** (not H2-only tests). With `WRITE=false`, test request, stage transition, reviewer task visibility, wrong reviewer/tenant rejection, cancel, reject, expiry, Outbox retry, dead-letter, idempotent repoll and audit; capture IDs and screenshots with secrets redacted. Do not enable SQL writes for approval testing.

Save `flowable-real-users.txt` and `flowable-outbox-state-sync.txt` including UTC timestamps, deployment digest, organization identifiers (masked), reviewer role matrix, exact tests, results and independent witness.

### PostgreSQL real source certification
For **each named version and datasource**: record TLS VERIFY_FULL CA+hostname, network allowlist, table/column grants, actual least-privilege READ credential, downgrade if UPDATE/CREATE is granted, sensitive column masking, read result row/byte budgets, 5-second default query timeout, client disconnect, SQL injection and immediate token/grant revocation. Verify physical READ cannot UPDATE regardless of SQL parser. MySQL does **not** inherit PostgreSQL certification.

Save `postgres-read-datasource-certification.txt` with approved source ID/host, scope, limitations and DBA approval. No unrestricted production SQL.

### Java Worker and UNKNOWN
On disposable target business-equivalent PostgreSQL and running **real Java Worker process**, test network partition; COMMIT before/after disconnect; JVM SIGKILL just after COMMIT; service restart; lease expiry; at-most-one target row change; UNKNOWN phase/alert; no automatic requeue; two different independent reconciliation operators; forbidden owner/reviewer self-attestation; incident evidence and operator recovery decision. Safeguard test data and attach immutable query output, task/attempt IDs and logs (no SQL text/secrets).

Save `worker-java-process-chaos.txt` and `unknown-two-person-reconciliation.txt`.

## 4. Production operations acceptance

- Network: production-only segmented network, trusted TLS certificates/renewal, DB and JVM no public ports; verify Caddy -> Web -> Gateway behavior and reverse proxy headers.
- Secrets: managed approved secret delivery, rotated prior exposed credentials, independent READ/WRITE/PLATFORM roles, no production secrets in Git, image or logs. Store evidence in `production-credential-rotation.txt`, `trusted-tls-and-network.txt`.
- Alerts: configure production **external HTTPS** receiver and verified HMAC key; test OPEN/retry/dedupe/RECOVERED, absence-of-data, on-call acknowledgement/escalation and independent incident archive. Store `alert-notification-delivery.txt`.
- Local backups: production GPG key (private key not on backup path), daily encrypted backups, retention and capacity alert, offline isolated restore, agreed RPO/RTO and WAL/PITR where required. Explicitly accept host-total-loss risk without offsite copies. Store `local-gpg-restore-and-retention.txt`.
- Security: run penetration tests for session/CSRF, IDOR, tenant isolation, malicious SQL, injection, XSS, MCP prompt injection, SSRF and secret exposure; sign off vulnerabilities and licensing/SBOM. Store `vulnerability-and-penetration-test.txt`.
- Capacity: authenticated end-to-end READ, approval/Outbox, approved WRITE under bounded safe synthetic load, prolonged soak, p95/error limits, DB pools, disk/CPU/memory, crash and immutable-image rollback. The existing anonymous 80-request test **does not qualify**. Store `load-soak-and-rollback.txt`.

## 5. Four authorized stages and rollback

Stages are **closed -> readonly -> approval -> write**. Set `DG_DEPLOY_STAGE` and run `bash bin/verify-staged-rollout.sh` on the release checkout with explicit feature flag environment. This command is **read-only**, does not deploy or enable anything.

| Stage | READ | Outbox | Queue / recovery / WRITE | Gate |
| --- | --- | --- | --- | --- |
| closed | false | false | false | safe initial state |
| readonly | true | false | false | signed candidate, certified datasource and host allowlist |
| approval | true | true | false | signed candidate + true preproduction role/Flowable/Outbox evidence |
| write | true | true | true | signed candidate + worker chaos and two-person reconciliation, independent authorization |

All stages require `FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED=false`. Every non-closed stage validates the three-role GPG-signed manifest against the **currently checked-out commit**; live feature authorization **cannot** be inferred from a CI success or a test environment secret.

Back out in reverse order: **disable WRITE and queue/recovery first** without replaying UNKNOWN; disable Outbox after pending events have been accounted for; disable READ when necessary. Preserve database and immutable release rollback image digest, logs, signed manifest and migration-compatibility proof. A rollback does not itself undo already committed SQL, and schema changes require explicit DBA evaluation.

## 6. Release decision

Use `DG_READINESS_EVIDENCE_DIR=/absolute/secure/path bash bin/check-production-readiness.sh` (12 required records, mode 0600/0640). Review the evidence contents separately and run `verify-signed-production-release.sh` with actual trusted approver keys and immutable digests. Only security, DBA and business authorized owners may sign. Do not submit fabricated/synthetic evidence.

Current result: **NO-GO** until all real external acceptance and signoffs have been completed.
