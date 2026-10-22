# PostgreSQL 17 平台数据模型、迁移及隔离设计（v0.4 · 单库双 Schema）

> ADR-008：使用 **一个 PostgreSQL 17 实例、一个 `dataguard` 数据库、两个 Schema**。平台业务表位于 `platform`；Flowable 的 `ACT_*` 位于 `flowable`；仍由各自角色和迁移工具管理。
> 状态：设计及初始化工程；尚未进行真实 PostgreSQL / Flowable 集成验收。

## 1. 物理存储边界

| 实例 | 数据库 | Schema | 管理者 | 迁移方式 | 账号 |
|---|---|---|---|---|---|
| PostgreSQL 17 单实例 | `dataguard` | `platform` | management-service，44 张 `dg_*` | Flyway V1 + Vn | `dg_platform` |
| 同一个实例 | **同一个 `dataguard`** | `flowable` | flowable-service，Flowable `ACT_*` | Flowable 对应版本官方机制 | `dg_flowable` |
| 目标业务库（独立于平台 PG） | 取决于数据源插件 | 取决于目标库 | read/write executor | 不由平台迁移 | 各业务库受限账号 |

开发环境 **仅有一个 `postgres:17` 容器**，绑定到 `127.0.0.1:55432`，使用单个持久卷 `dg_postgres`。数据库由官方 Postgres 镜像创建；首次空卷启动时 `deploy/postgres/init/01-create-schemas.sh` 创建两位受限角色和 Schema。应用程序不能通过管理员账号跨 Schema 访问。

数据库名字 `dataguard` 与 Schema 名 `platform`/`flowable` 为固定设计值。JDBC URL 分别使用 `currentSchema=platform` 与 `currentSchema=flowable`，Spring 配置 `spring.flyway.default-schema=platform` 和 `flowable.database-schema=flowable`；这些设置在当前 Flowable 8 版本下仍须实际集成验证。

**隔离边界**：Schema 隔离是 SQL 对象/权限隔离，不是独立 CPU/IO/容灾隔离。数据库级备份、WAL/PITR、恢复应覆盖同一个物理数据库；Flowable 审批状态和平台执行任务跨进程并非天然同一事务。不得依赖共享 PG 来省略 Outbox/CAS/UNKNOWN 故障处理。

**生产账户**：开发模板的 `dg_platform` 兼任 Schema owner/Flyway 迁移账号以便初始化；正式生产还需拆分 `dg_platform_migrator` 与受限 DML runtime 角色，Flowable 同理考虑迁移权限收敛，生产登录密码建议使用 Secrets/Vault 而非 env 明文。

## 2. PostgreSQL 数据类型与约束

| 语义 | PostgreSQL DDL | 与 MySQL v0.2 差异 |
|---|---|---|
| 标识符 | `UUID`（应用产生 UUID） | 旧 `CHAR(36)` 不再使用 |
| 时间 | `TIMESTAMPTZ` | 替换 `DATETIME(3)`；应用展示统一 UTC |
| 规则/策略 | `JSONB NOT NULL DEFAULT '{}'::jsonb` | 替换 `JSON`，可按需求增加 GIN/表达式索引 |
| 密文 | `BYTEA` | 替换 `LONGBLOB`，仅存加密 SQL/参数，Secret 引用在 Vault 等 |
| 布尔值 | `BOOLEAN` | 替换 `TINYINT(1)` |
| 操作集合 | `TEXT[]` | 替换 JSON 数组；应用严格限制合法枚举值 |
| 部分索引 | `CREATE INDEX ... WHERE ...` | 用于待执行任务、未读通知等热点查询 |
| 并发领取 | `FOR UPDATE SKIP LOCKED` | PG 下使用 `now()` 和 `INTERVAL` |
| OIDC 身份 | `CHAR(64)` SHA-256 摘要 + 原文 | 保留 v0.2 索引长度风险改进，摘要匹配后必须复核原始身份字段 |

OIDC canonical digest 应由应用层对 **带长度前缀的 issuer、subject、audience 的 UTF-8 原始字节** 做 SHA-256 计算；用户 OIDC 使用 issuer+subject；Agent 身份使用 issuer+subject+audience。不能不带分隔直接拼接；必须复核原值及租户权限。未经明确版本管理不得改变规范化算法。

## 3. 44 张业务表完整范围

| 领域 | 业务表 |
|---|---|
| 组织/RBAC | dg_organization, dg_user, dg_role, dg_user_role, dg_user_session |
| Agent/权限 | dg_agent, dg_agent_identity, dg_agent_permission, dg_object_grant, dg_agent_usage_daily |
| 数据源 | dg_datasource, dg_datasource_secret, dg_datasource_capability, dg_schema_cache |
| 安全规则/脱敏 | dg_sql_policy, dg_sql_policy_version, dg_mask_rule |
| BPMN 业务映射 | dg_workflow_template, dg_workflow_version, dg_workflow_binding |
| SQL | dg_sql_request, dg_sql_request_payload, dg_sql_read_summary |
| 审批 | dg_sql_approval, dg_sql_approval_action, dg_approval_attachment, dg_approval_delegation |
| 执行 | dg_execution_task, dg_execution_attempt, dg_outbox_event |
| 审计 | dg_audit_stream, dg_audit_event, dg_audit_anchor, dg_system_event |
| 告警/通知 | dg_webhook_endpoint, dg_webhook_delivery, dg_notification, dg_alert_rule, dg_alert_event |
| 部署治理 | dg_plugin_registry, dg_bootstrap_state, dg_migration_record, dg_backup_job, dg_security_incident |

平台业务表不包含任何 `ACT_*`；全部外键使用 PG 原生约束，保留 `(org_id,id)` 复合外键防跨租户误引用。敏感表必须以应用强制组织过滤 + OIDC 权限 + PG role 分离共同防护。

## 4. PG RLS 逐步启用方案（不在 V1 自动打开）

RLS 是未来加固措施而不是 V1 当前已实现功能。未经运行时 `SET LOCAL app.tenant_id`、连接池复用清理、管理迁移账号与业务角色分离验证，**禁止直接对全部表启用 RLS**。

可在测试环境按以下步骤针对 `dg_datasource` 试点，脚本位于 `database/postgresql/optional/tenant_rls_pilot.sql`：

1. 区分 `dataguard_owner` 迁移角色和 `dataguard_runtime` 最小权限运行角色，确保运行账号没有 `BYPASSRLS`，不是超级用户。
2. 事务开始后从认证上下文验证 org_id，再对当前事务执行 `SET LOCAL app.tenant_id = '<validated-uuid>'`；连接池复用不得遗留租户上下文。
3. 在测试角色上启用 `ENABLE ROW LEVEL SECURITY` 并设置 `USING`/`WITH CHECK`；必须测试缺省 tenant_id、非法 UUID、跨组织请求与批量操作。
4. 如需让表所有者同样受限，考虑 `FORCE ROW LEVEL SECURITY`，但 Flyway 迁移角色与安全策略要单独验证；所有者/超级用户、BYPASSRLS 与 `TRUNCATE` 有特殊语义。

RLS 不替代 Agent 级工具授权、SQL 安全审查、目标业务数据库的原生 ACL。RLS 仅保护 **平台元数据**，不会自动约束 Agent 连接的目标数据库。

## 5. 任务领取和故障恢复（PG）

```sql
BEGIN;
SELECT id FROM dg_execution_task
 WHERE task_state='QUEUED'
   AND authorization_expires_at > now()
 ORDER BY enqueued_at, id
 LIMIT 1 FOR UPDATE SKIP LOCKED;
-- 用选中 id 做条件更新，必须检查更新行数恰为 1
UPDATE dg_execution_task
 SET task_state='CLAIMED', lease_owner=:worker_id,
     lease_until=now()+INTERVAL '30 seconds',
     fencing_token=fencing_token+1, attempt_no=attempt_no+1,
     updated_at=now(), version=version+1
 WHERE id=:task_id AND task_state='QUEUED';
COMMIT;
```

一旦写执行器已向目标库发送 SQL 但没有确认结果，状态应是 `UNKNOWN`，**不得依赖租约到期自动重复执行非幂等 DML**。Flowable 不持有业务库写入账号，也不直接执行 SQL。所有审批授权绑定 SQL 快照、参数、Agent、数据源、策略版本和时效。

## 6. 索引、审计与备份

- `JSONB` 可选 GIN，但没有明确查询模式之前不滥建；先用 `EXPLAIN (ANALYZE, BUFFERS)` 与 P95/P99 压测优化。
- `dg_audit_event` 仍采用单表 + 普通索引和哈希链；大量写入时另起版本迁移做按时间分区和归档，不能直接替换成未经验证的分区表并破坏唯一约束。
- 审计哈希链不等于绝对防篡改；外部存证/不可变归档及密钥轮换仍为必要设计。
- 对唯一的 `dataguard` 数据库实施一次统一 WAL/PITR 备份；按 Schema 的逻辑 `pg_dump` 可做辅助归档，但不能替代一致的整库恢复。恢复后仍须核对流程实例、平台审批状态与未投递 outbox。
- 生产应把 Flyway 迁移权限与运行时 DML 权限分离。开发 Compose 提供的超权限初始化账号仅用于本地快速启动，不可照搬生产。

## 7. 同库双 Schema 初始化与 v0.3 升级

- 全新安装：`cp deploy/.env.example deploy/.env`，修改三个独立的数据库密码、OIDC；运行 `./bin/bootstrap-dev.sh --start-db`。卷为空时初始化脚本只运行一次。应用使用配置分别建 `platform.flyway_schema_history`、`flowable.ACT_*`。
- v0.3 两个独立 PG 已存在数据：**不能**简单改 Compose 并复用旧卷；新配置只创建新单库。真实数据必须使用专门的 pg_dump/pg_restore+Schema 映射和审批/Flowable 完整性对账方案；本交付包**不提供**未经验证的自动数据合并迁移工具。
- 因 v0.3 初始化工程未发布生产，空白环境可以保留旧卷备份后新建 v0.4 卷；不要直接 `docker compose down -v` 清理真实数据。
- Flyway 只管理 `platform`，Flowable 内部 `ACT_*` 的初始化与升级由其自身引擎负责。正式发布过的 V1 不得原地改写，需追加 V2；本 V1 是未投产的全新安装基线。

## 8. 变更与升级规则

本 v0.4 **未继承 MySQL 已上线数据**；使用新的 PostgreSQL `V1__platform_baseline.sql` 作为全新安装版本。已有 MySQL 非空库需要独立 ETL、完整性校验、停机切换和可回滚演练，不能直接运行 PG V1 当作跨库迁移。

已发布到生产的 Flyway V1 永不覆盖，后续仅追加 V2、V3；Flowable 升级由其自有版本机制管理，不得将 ACT_* 加入平台 Flyway。

## 9. 验收用例

- PG17 上成功迁移 44 表；枚举类型、外键、部分索引、UUID/JSONB/TIMESTAMPTZ 正确；二次 migrate 幂等。
- 管理服务仅能访问 `platform` Schema；Flowable 服务仅能访问 `flowable` Schema；目标库按 SPI 访问。
- OIDC 摘要索引与原值核对；跨租户访问拒绝，未知 Agent 默认拒绝。
- approval outbox、`SKIP LOCKED` 竞争、fencing/UNKNOWN、人为断线重复执行防护。
- `RLS` 试点另测，不得把未完成的 RLS 当成生产已启用。
- 真实 Postgres 迁移、Maven 构建、Flowable 流程测试、OIDC 集成测试均列为待执行门槛。

## 参考

- https://www.postgresql.org/docs/17/ddl-rowsecurity.html
- https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database
- https://www.flowable.com/open-source/docs/bpmn/ch05a-Spring-Boot/