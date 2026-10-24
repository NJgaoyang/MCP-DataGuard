# Production deployment — hard stop until actual signoffs

Production is **NO-GO** while real proof of role-specific approvals, certified target PostgreSQL, encrypted local restore, live webhook, security/load testing, and signed immutable releases is missing.

## Deployment architecture

`deploy/compose.production.yaml` is a stand-alone production Compose file with no local PostgreSQL, no build steps and no published JVM or nginx host ports. Approved independent PostgreSQL platform and Flowable schemas must exist and be TLS `verify-full` with an approved PEM CA mounted at `/run/secrets/postgres_ca` and explicit JDBC `sslrootcert=/run/secrets/postgres_ca`; verify-full must validate the hostname. Images MUST use full `registry/...@sha256:...` immutable references for gateway, workflow, web and Caddy. Signed release manifest binds the first three images.

Do not combine it with `compose.dev.yaml`. Caddyfile.production requires a DNS name and uses normal ACME; confirm real certificate and expiry from an external network, do not assume that successful Compose parsing proves HTTPS.

## Host preparation (authorized operator only)

Provide `DG_GATEWAY_IMAGE`, `DG_WORKFLOW_IMAGE`, `DG_WEB_IMAGE`, `DG_CADDY_IMAGE`, `DG_PUBLIC_HOST`, `DG_PLATFORM_JDBC_URL`, `DG_FLOWABLE_JDBC_URL`, `DG_PLATFORM_USER`, `DG_FLOWABLE_USER`, and full absolute paths `DG_PROD_PLATFORM_PASSWORD_FILE`, `DG_PROD_FLOWABLE_PASSWORD_FILE`, `DG_PROD_POSTGRES_CA_FILE` to separately managed secrets (owner root, group 10001, mode 0640 (required for non-root JVM in rootful Docker), no symlinks). Never commit, print or stage secrets. Rootless JVM service reads mounted file secrets at process launch and passes credentials in its own JVM environment; host root and process readers therefore still require least privilege. Do not mistake Docker file secrets for a full Vault/KMS rotation design.

The platform DB must be fully migrated with audited backup/rollback before launching; Flowable `FLOWABLE_SCHEMA_UPDATE=false` to block automatic DDL. Runtime feature flags are false by default. Establish named production incident receiver and trusted approver keys. The Caddy state volumes are the only Docker volumes managed by this Compose.

## Required execution sequence

1. Run `bash bin/verify-production-compose.sh` (strict immutable images, host credential files, external TLS databases). It does **not deploy**.
2. Run `DG_READINESS_EVIDENCE_DIR=/approved/secure/path bash bin/check-production-readiness.sh`; independent DBA/security/business review the *contents* of 12 evidence records.
3. Run `DG_DEPLOY_STAGE=closed bash bin/verify-staged-rollout.sh`. All five execution/approval flags must remain false.
4. Before each non-closed stage, re-run stage preflight and GPG 3-person release manifest verification bound to exact checkout SHA and immutable image digests.
5. Under approved change control only, use `docker compose -f deploy/compose.production.yaml --profile workflow config` and approved `up -d --no-build`. This is a manual action; scripts do not silently execute deployment.
6. Check process and ingress status; then run `bash bin/verify-production-runtime.sh` to compare running container image references, flags and privilege settings. Monitor 5xx, database sessions, Outbox, UNKNOWN, incident receipt and SSL.
7. Roll back using **approved previous SHA-pinned images**, not `latest`; disable WRITE/queue/recovery immediately and preserve UNKNOWN for two-human reconciliation. Drain and account for Outbox before disabling. Never replay ambiguous commits. Schema downgrade requires DBA-led migration review.

## Non-goals and remaining external requirements

No offsite backup is requested; loss of entire production host remains an accepted *only if signed* residual risk. This Compose cannot configure external DB ACL, cert authority, real staff accounts, pager service, GPG signers, Vault/KMS, image registry access or penetration test vendors. Their evidence is mandatory before GO.

## Read-only change-control gates

`DG_PRODUCTION_CHANGE_ID=... DG_DEPLOY_STAGE=... bash bin/verify-production-deploy-preflight.sh` binds the signed manifest, actual checkout, production Compose images, named real evidence, and explicit stage flags without deploying. After an authorized manual Compose deployment, `bash bin/verify-production-runtime.sh` verifies that live containers match signed digest image inputs, role privileges, exposed ports and flags. Neither utility grants production authorization.

### 运行态全面核验

`verify-production-runtime.sh` 在核对三方 GPG 签名之后，检查生产 Compose 项目的每个容器和每个受信 Docker RepoDigest，**包括 closed/readonly 下仍在运行的旧 Workflow**。拒绝遗留开启 Outbox、旁路开启受控写入、未签名镜像、非法容器、宿主机暴露网关端口及明文 Docker Config.Env 密钥。它只读，不会暂停数据库或容器。
