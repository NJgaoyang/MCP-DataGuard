# DataGuard 生产上线 Go / No-Go 清单

更新：2026-10-24，代码分支 `dev20261022`。此表是**验收门禁**，不是已经获批的生产上线证明。没有现场证据时必须按未通过处理。

**业务范围已确认：不实施异地备份。** 生产上线不要求 S3、Object Lock、云端 KMS、异地上传或另一台主机恢复。继续要求本机 PostgreSQL 加密备份、独立保管恢复密钥、定时任务、备份保留策略及隔离恢复演练。必须接受单机整体损毁时可能同时丢失服务和本地备份的剩余风险。

## 1. 当前已实现 / 已验证

| 领域 | 实现 | 证据 |
|---|---|---|
| 真实 MCP 只读 | Agent Token → MCP HTTP → 已授权 SELECT → TLS → 物理只读 → 默认脱敏 → 审计；权限和 Token 撤销 | `bin/verify-mcp-authorized-read-http.sh` |
| Flowable | 两级独立审批人、角色和自批限制、拒绝、取消、超时、Outbox 回放 | `ApprovalReviewerRoleIntegrationTest` 与 Flowable 测试 |
| 安全写入 | 一次性授权、Worker、UNKNOWN 隔离、提交后确认丢失模拟及禁止自动重放 | `ApprovedWritePipelineIntegrationTest`、`ExecutionRecoveryWatchdogIntegrationTest` |
| 多租户账号 | 明确的组织编码登录、单组织兼容、跨组织同名账号隔离、登录审计 | `NativeMultiTenantLoginIntegrationTest` |
| MySQL | 8.4 测试连接、TLS 身份校验、物理只读账号、SQL 拒绝测试 | `bin/verify-mysql-connection-test.sh`，**不认证 MySQL MCP 执行** |
| 生产容器加固配置 | `read_only`、临时目录、no-new-privileges、能力削减、资源限制、日志轮转 | `deploy/compose.hardened.yaml`、`bin/verify-hardened-compose.sh` |
| 加密备份恢复 | GPG 公钥加密流式 `pg_dump`、SHA-256、离线一次性 PG 恢复 | `bin/backup-postgres-encrypted.sh`、`bin/verify-encrypted-backup-restore.sh` |
| 安全响应头 | API/MCP `no-store`，防嵌入/禁用敏感设备权限，CSP 报告模式 | `deploy/Caddyfile.public` |
| 发布检查 | 配置、加密备份的 CI 恢复演练、后端 CI、浏览器测试 | GitHub Actions |

**禁止推断：** 安全配置文件通过静态验证，并不代表各容器已切换到加固后的运行参数。备份脚本通过测试，并不代表本机定时备份、保留期或恢复值班责任已经配置。

## 2. 必须由部署负责人提供现场验收证据的 P0 阻断项

- [ ] 部署至**独立的生产网段**，正式域名、可信 CA 证书、80/443 入口，数据库 / JVM / MCP 无绕过鉴权的公网入口。Dev 使用的 IP HTTPS 不是生产证书验收。
- [ ] 使用**不可变镜像 digest**、签名 / SBOM / 漏洞扫描；建立回滚版本与变更审批记录，禁止生产拉取浮动 `latest`。
- [ ] 正式密钥保存在组织认可的 Vault / KMS / Secret Manager，执行器与平台使用不同最小权限账号。测试证书、测试密码、历史凭据全部轮换；Docker 环境变量中的生产密码需要迁移到正式凭据分发体系。
- [ ] **真实**生产用户身份、双审批人、权限隔离、拒绝、取消、到期、工作流版本冻结及审批日志在预生产环境完整验收。不得开放未经认证的 Flowable 消费。
- [ ] PostgreSQL 只读认证明确到数据库版本、授权范围、表列权限、数据脱敏、SQL 超时、行/字节预算；真实业务敏感字段不泄露，且令牌吊销实时生效。
- [ ] **写入故障混沌验收**：网络分区、TCP 断开、数据库 COMMIT 后进程崩溃、应用重启、租约超时。任何 UNKNOWN 都必须人工对账，自动重试次数为 0。
- [ ] 告警送达明确值班人，验证通知的实时性、去重、误报关闭、审计留存、防篡改外部锚定和检测不到消息时的降级策略。
- [ ] 密码喷洒、跨租户、IDOR、SQL 注入、XSS、CSRF（Token 模式）、MCP Prompt Injection、恶意 SQL、凭据轮换、会话撤销、默认密码负向测试。
- [ ] 负载 / 资源压测，确定 QPS、P95、故障率、数据库连接池和容量阈值，确认加固后的 `mem_limit`/CPU 限制在实测峰值下足够。
- [ ] **本地恢复可证明**：从本机加密备份恢复到隔离的一次性 PostgreSQL 容器，记录 RPO/RTO；单库 `pg_dump` 不含 WAL/PITR、数据库全局角色及分布式一致性，需 DBA 根据业务恢复目标确认本地 WAL/PITR、全局角色备份和定期恢复演练。不要求异地存储。
- [ ] GitHub 分支保护、审批发布权限、操作日志、漏洞分级响应、回滚与应急操作 SOP，经 DBA/安全/业务负责人的书面 Go / No-Go 签字。

## 3. 加密备份部署

**生产前提**：专用 GPG 公钥导入 `/etc/dataguard/backup-public-keyring`（权限 0700），私钥必须**与备份目录分开授权保管**；`/var/lib/dataguard/encrypted-backups` 权限 0700，不与数据库数据卷共用。

在 `/etc/dataguard/backup.env`（仅 root 可读，0600）中设置：
```ini
DG_BACKUP_CONTAINER=deploy-postgres-1
DG_BACKUP_DB_USER=dataguard_bootstrap
DG_BACKUP_DATABASE=dataguard
DG_BACKUP_DIRECTORY=/var/lib/dataguard/encrypted-backups
DG_BACKUP_RECIPIENT=YOUR_FULL_40_OR_64_CHAR_HEX_GPG_FINGERPRINT
```

经生产密钥审批和首次人工备份恢复演练后，运维才可安装：

```bash
install -m 0755 bin/backup-postgres-encrypted.sh /usr/local/libexec/dataguard/backup-postgres-encrypted.sh
install -m 0644 deploy/systemd/dataguard-postgres-backup.service /etc/systemd/system/
install -m 0644 deploy/systemd/dataguard-postgres-backup.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now dataguard-postgres-backup.timer
systemctl list-timers dataguard-postgres-backup.timer
```

**注意**：上面是批准后的安装步骤，脚本本身不会自动启用生产备份服务。每日 timer 为 02:00 UTC，最多随机延后 10 分钟。还需落实本机备份保留期与容量告警、解密私钥恢复流程及周期性隔离恢复验证；不安排异地转储。

恢复验收应在独立主机、使用受控离机私钥运行：

```bash
DG_RESTORE_VERIFY_ARCHIVE=/var/lib/dataguard/encrypted-backups/dataguard-<date>.dump.gpg \
  bash bin/verify-encrypted-backup-restore.sh
```

此恢复脚本**只会创建一次性、无网络、无生产数据卷的 PostgreSQL 容器**，完成后自动清理。不是正式恢复生产数据库的命令。

## 4. 发布执行顺序

1. 检查 `docker compose` 加固静态门禁和 Caddy 配置：`bash bin/verify-hardened-compose.sh`。
2. 运行 `DG_RUN_NATIVE_MCP_HTTP_E2E=true DG_RUN_MCP_READ_ALLOW_HTTP_E2E=true bash bin/verify-core-security-gates.sh`。
3. 在受控 MySQL 测试机完成 `bash bin/verify-mysql-connection-test.sh`，不得将 MySQL 连接测试误判为 MySQL 已获得写入认证。
4. 使用隔离备份 + 加密恢复和权限负向测试验证发布候选；提前检查 Docker 资源限制、容量及回滚镜像。
5. 严格按阶段开放：只读白名单 → 审批业务 → 受控写入。未经安全负责人签字，`DG_ENABLE_READ_EXECUTION=false`、`DG_ENABLE_WRITE_EXECUTION=false`、`FLOWABLE_OUTBOX_ENABLED=false`、`FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED=false` 必须保持关闭。

## 5. 明确的当前生产上线结论

**NO-GO**。软件层完成了上述加固和隔离测试，但生产密钥、本地自动备份与恢复目标、正式域名与 TLS、实际网络故障注入、生产审批身份联调、生产负载/渗透测试、值班告警和批准手续仍需由环境持有人执行。不要将任何测试报告改写成生产可用证明。

## 6. 2026-10-24 后续 P0 实施增量与上线约束

- **真实 TCP/COMMIT 故障演练已实现**：`bin/verify-real-postgres-commit-disconnect.sh` 在专属 Docker 隔离网段、一次性 PostgreSQL 和独立客户端中，真实调用 `pg_terminate_backend`。验证 COMMIT 之前断连导致回滚；存储过程在 COMMIT 之后、CALL 返回之前断连时，客户端收到错误但目标库物理提交保持可见。这是数据库底层故障的实证；对 DataGuard 任务 `UNKNOWN`、拒绝重复执行的验证见 `ExecutionRecoveryWatchdogIntegrationTest`。
- **Flowable 任务权限负向验收**：`ReviewProcessAuthorizationIntegrationTest` 使用真实 Flowable 引擎和 BPMN 验证禁止未领取任务提交、非当前审批人、跨租户任务访问、拒绝处理以及审批不能启用 SQL 写入。与 PostgreSQL 审批隔离测试合计 **22 个核心集成用例**。
- **异地备份已移出范围**：不要求 S3 Object Lock、KMS、IAM、异地上传或跨主机恢复，不作为 P0 验收条件。历史可选脚本不会由标准发布流程调用；保留本地加密备份、定时调度与隔离恢复验收。
- **发布签字门禁**：`bin/verify-signed-production-release.sh` 必须校验不同的安全、DBA、业务 GPG 签名、短期有效期、不可变镜像 Digest，以及所有 P0 上线证据。`bin/verify-signed-release-policy.sh` 验证篡改、过期、同一签名人兼任审批角色被拒绝。**目前尚未获得三方正式签名，因此生产发布仍应阻断。**
- **PostgreSQL 不停机资源控制已实施**：实际运行容器内存/交换空间各上限 1.5 GiB，CPU 2 核、PID 256；数据库查询和网关健康检查通过。Compose `deploy/compose.hardened.yaml` 已持久化配置。**PostgreSQL 容器的 no-new-privileges 尚未生效**，需经数据库维护窗口重建容器并验证恢复与回滚。
- GitHub `production-hardening.yml` 已集成真实 TCP COMMIT 断连、GPG 三方签名检查、本地加密备份恢复演练。该流水线只使用一次性模拟身份和数据；本地备份恢复操作和正式审批仍须现场验收。

### 本轮已经验证的命令

```bash
DG_RUN_NATIVE_MCP_HTTP_E2E=true \
DG_RUN_MCP_READ_ALLOW_HTTP_E2E=true \
DG_RUN_REAL_TCP_COMMIT_E2E=true \
bash bin/verify-core-security-gates.sh

bash bin/verify-hardened-compose.sh
bash bin/verify-live-hardened-dev.sh
bash bin/verify-signed-release-policy.sh
```

### 剩余真实生产 P0（没有现场证据则为 NO-GO）

1. 生产审批用户与实际 Flowable 消费队列的业务身份联调及审批责任确认。
2. 完整 DataGuard Worker 进程 SIGKILL/网络分区与数据库提交交叉时序的实机故障演练，以及 UNKNOWN 人工对账操作演练。已有真实数据库断连试验不能替代此项。
3. 正式本机加密备份定时器启用、备份容量与保留期治理、隔离恢复演练和经 DBA 确认的 RPO/RTO（根据业务目标制定本地 WAL/PITR 方案）。
4. 正式域名与受信 CA、IAM/Vault 密钥轮换、生产级告警通知渠道、外部审计存证、性能和渗透测试及受保护镜像/发布链路。
5. DBA 维护窗口内重建 PostgreSQL 容器应用启动时才能配置的安全约束，并在维护窗口前完成恢复演练。
6. 完整三方签名的正式发布证据包与 Go/No-Go 审批。

**上线结论保持 NO-GO**：继续使用开发环境，所有真实 SQL 写入开关维持关闭，生产人员与密钥缺失时不能绕开门禁。
