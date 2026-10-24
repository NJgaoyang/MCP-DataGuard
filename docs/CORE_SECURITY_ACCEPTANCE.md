# DataGuard 核心安全闭环：实现与验收门禁（2026-10-24）

> 目标分支：`dev20261022`。本文区分「隔离集成测试通过」与「生产可用」。
> **禁止以 CI 编译成功或 SQL 语法预检通过为由开启真实读写。**

## 1. 核心模块与本轮新增内容

| 领域 | 代码实现与验收入口 | 当前上线门禁 |
| --- | --- | --- |
| 01 Agent / MCP 只读 | `AgentReadMcpIntegrationTest`：Agent 身份→数据源能力认证→工具 ACL→精确字段权限→SQL 策略→TLS 物理只读→结果脱敏→哈希链/回执→权限撤销后立即拒绝；`ReadOnlyJdbcTlsIntegrationTest` 验证只读权限和资源预算。本轮修复损坏的 mask 规则 SQL。 | 仅隔离 PG 验收，生产默认 `DG_ENABLE_READ_EXECUTION=false` |
| 02 Flowable 审批 | `ApprovalOutboxIntegrationTest` 两级流程；`ApprovalRecoveryIntegrationTest` 超时、重试、死信告警。 | 业务动态部署 / Outbox 默认关闭，需现场流程身份与业务联调 |
| 03 受控写入 | `ApprovedWritePipelineIntegrationTest` 一次性授权队列、物理写入、UNKNOWN 隔离、禁止自动重放；`CertifiedWriteJdbcTlsTest` TLS 与最小权限。 | 正式写入默认禁用；需远程提交不确定性故障注入 |
| 04 告警与审计 | 新增 `DeniedSqlAlertService`；V4 限制同组织同规则同指纹 OPEN 告警唯一；`DeniedSqlAlertIntegrationTest` 检验跨租户隔离、去重、确认后重开；新增 `/api/v1/operations/live` 五分钟聚合指标及 RBAC 测试；`AuditIntegrityIntegrationTest` 检验篡改。 | 事件推送、通知通道、外部不可篡改锚定尚未完成 |
| 05 系统验收与交付 | `bin/verify-core-security-gates.sh` 串联全部真实 PG 用例，并且强制检查报告非跳过；临时容器执行 `pg_dump/pg_restore`，验证结构及合成数据；`tests/browser` + 浏览器工作流检查登录、导航、弹窗与宽度适配。 | 真实外部 MCP 客户端、生产规模恢复演练、安全压测和故障混沌演练需独立通过 |

## 2. 一键隔离验证

准备 Docker + Compose，以及可访问官方 Maven Central 的测试环境：

```bash
# 首次构建依赖缓存会使用临时 Maven 容器访问 Maven Central。
# 实际数据库用例运行于 --network none 的临时 PostgreSQL 17 命名空间。
DG_E2E_PREFETCH_MAVEN=true bash bin/verify-core-security-gates.sh

# 之后依赖已存在缓存，可直接：
bash bin/verify-core-security-gates.sh
```

脚本要求七组真实 PostgreSQL 集成测试的 XML 报告都存在，且
`tests > 0`、`skipped = 0`、`errors = 0`、`failures = 0`；
任一检查失败均不允许打印 `FULL_ISOLATED_CORE_SECURITY_ACCEPTANCE_PASS`。

测试容器不映射端口、不挂载现有 `deploy_pg_data` 卷；
所有测试密码只用于一次性测试用户和临时容器。
脚本不修改生产数据库、不启用任何真实读写功能、不提交或打印真实密钥。

## 3. 非隔离生产上线前的明确阻断项

1. **真实 MCP 协议**：在隔离网关与平台上以真实 Agent Token 完成 Streamable HTTP 握手、调用、权限吊销、跨租户拒绝和异常清理。工具方法级测试不等于协议联调。
2. **认证与浏览器**：完成真实登录、强制改密、新密码登录、会话撤销、账号锁定的浏览器验证；浏览器静态或模拟 API 测试不等于现场身份测试。
3. **只读数据库认证**：逐个数据库版本独立认证 TLS、数据库 READ 权限、数据源白名单、数据脱敏密钥、SQL 形态、资源预算和日志审计。其他数据库不得借用 PostgreSQL 认证结果。
4. **审批身份隔离**：确保审批角色和真实 Flowable 任务使用同一主体，审批人不得自批、不得跳步；重复消费、取消、并发审批必须可证明。
5. **写入故障注入**：远程 COMMIT 时强制断链、杀进程、网络分区，确认 UNKNOWN 只能人工核对，不得自动重放。
6. **告警与监控**：定义事件推送和通知、恢复去重策略、审计外部锚定及留存期限；当前 Live API 是五分钟窗口轮询，不是流式推送。
7. **交付**：备份加密、恢复时间目标、恢复点目标、灾难恢复演练、渗透测试、依赖与许可证扫描、浏览器可访问性和日志脱敏检查，形成有责任人的签字验收。

## 4. 发布安全状态

- `DG_ENABLE_READ_EXECUTION=false`
- `DG_ENABLE_WRITE_EXECUTION=false`
- `DG_WRITE_QUEUE_ENABLED=false`
- `DG_WRITE_RECOVERY_ENABLED=false`
- `FLOWABLE_OUTBOX_ENABLED=false`
- `FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED=false`

**以上值是未认证生产环境的安全基线，不得因测试通过而全局改为 true。**

> 要启用某一个被认证的功能，必须先有明确的目标数据库、被认证版本、
> 权限和凭据分离、隔离环境证据、可回滚部署步骤，以及显式上线批准。

## 5. 第二轮五阶段交付增量与真实协议证据（2026-10-24）

本轮在隔离 PostgreSQL 与网关环境中增加以下实现，**并不意味着生产执行已启用**：

- **只读执行与监控**：`ReadOnlyQueryService` 按实际 JDBC 执行时间调用 `ReadReceiptService`，把 `returned_rows`、`result_bytes`、`duration_ms` 原子写入 `dg_sql_read_summary`。P95 现在有真实记录来源，写入失败时读结果不会正常下发。
- **真实 MCP HTTP**：`bin/verify-native-mcp-http.sh` 在两个隔离的一次性 Docker 容器中验证真实 Agent Token、`initialize`、`tools/list`、`tools/call`、默认禁用读取、即时 Token 撤销。只验证安全拒绝，不伪称生产查询已开放。
- **审批取消与 Flowable**：`POST /api/v1/approvals/{approvalId}/cancel` 只接受匹配版本的 `PENDING/NOT_SCHEDULED`，记录 `CANCEL` 审批动作和 `SQL_APPROVAL_CANCELLED` 操作日志，发布 `APPROVAL_CANCELLED` Outbox；Flowable 在验证租户后关闭同一流程实例。多人审批与过期、拒绝的原有限制保持不变。
- **写入不确定性监控**：`ExecutionRecoveryWatchdog` 对过期 `CLAIMED/EXECUTING` 租约标记 `UNKNOWN`，生成不包含 SQL 明文的 `WRITE_EXECUTION_UNKNOWN` 严重告警。新增真实 PG 回归确认不会自动增加执行次数，也不会重复告警。
- **告警生命周期**：`GET /api/v1/security-alerts` 可按状态/等级/规则筛选，限制最多 100 条且强制组织隔离；`POST /api/v1/security-alerts/{alertId}/resolve` 要求先确认，留下独立审计事件。后台页面提供筛选、确认及解决操作。

### 对应验收命令

```bash
# 只运行隔离 PG/Flowable/写入安全闭环，以及真实 MCP HTTP 测试
DG_RUN_NATIVE_MCP_HTTP_E2E=true bash bin/verify-core-security-gates.sh

# 发版时验证指定候选镜像；不得使用未声明的生产密钥
DG_MCP_GATEWAY_IMAGE=deploy-gateway:five-stage-candidate-20261024 \
DG_RUN_NATIVE_MCP_HTTP_E2E=true bash bin/verify-core-security-gates.sh
```

实际执行的隔离 PostgreSQL 集成测试包括原有 17 个加 2 个 `ExecutionRecoveryWatchdogIntegrationTest`，共 **19 个用例**（零失败、零错误、零跳过）。MCP 协议门禁另外校验三份完整 JSON-RPC 结果及吊销行为，不计入上述 JUnit 数量。

### 仍然禁止宣称完成的生产级项目

必须由现场系统单独验收：MCP 真实只读 **ALLOW**（对指定 TLS PostgreSQL、完成脱敏和审计）；真实业务审批人的 Flowable 任务身份验证；受控写入远程 COMMIT 断链和故障注入；通知通道及不可篡改外部审计存证；生产规模的性能、安全、灾备演练。项目中已经存在的执行开关默认关闭，不得以隔离环境成功替代上线批准。
