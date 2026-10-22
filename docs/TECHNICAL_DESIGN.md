> **2026-10-22 实施决议（v0.4）**：统一使用 **1 个 PostgreSQL 17 实例 / 1 个 `dataguard` 数据库**；`platform` Schema 存储 44 张产品业务表，由 Flyway 迁移；`flowable` Schema 存储 Flowable 引擎表，由 Flowable 自有迁移管理；分别使用 `dg_platform` / `dg_flowable` 受限角色和连接池。MySQL 仍是业务目标数据库插件。此实施决议优先于历史示例。


# MCP DataGuard 开源技术架构与详细设计

> **文档状态**：技术设计基线草案，供架构评审、研发拆分及安全审查；非现有系统实现证明  
> **版本**：v1.0-draft · **日期**：2026-10-21  
> **关联文档**：《MCP DataGuard 开源产品需求文档（PRD）》  
> **技术主线**：Spring Boot 4 + Spring AI 2 MCP Server + Flowable 8 + ShardingSphere SQL Parser（SPI 封装）+ JDBC 多数据库插件 + Vue 3  
> **开源定位**：建议 Apache-2.0；不开发商业授权、激活或功能收费模块

## 1. 技术目标、假设与硬性边界

### 1.1 设计目标

为 AI Agent 提供受控的 MCP 数据库工具服务，实现“身份校验 → 工具授权 → SQL 方言解析与语义抽取 → 数据源/对象权限 → 风险规则 → 自动安全查询或 Flowable 审批 → 独立写入执行 → 全链路审计”。以多数据库插件为扩展边界，MySQL、PostgreSQL 为 V1.0 完整认证目标。

### 1.2 非目标

不开发透明 JDBC/TCP 数据库代理；不承担所有直连数据库的流量审计；不做跨库联邦 SQL 优化；不把 Flowable 当作具备数据库权限的执行器；不自动将未知 SQL 推给人工审批以绕过解析失败；不将匿名公网 API 作为默认访问方式。

### 1.3 安全不变量（必须由自动化测试证明）

- S-01：未经身份和资源权限验证的 MCP 请求，不触达数据库。
- S-02：只读执行器数据库账户在物理数据库层不具备写入权限。
- S-03：MCP/Web/API 管理服务不得持有写入执行器的高权限凭据。
- S-04：解析不完整、危险副作用未知、跨库授权不清时一律拒绝。
- S-05：审批人不能修改已提交 SQL/参数/目标库，不能审批自己发起的工单。
- S-06：Flowable 的“通过”只生成有约束的执行许可，不能直接调用数据库写接口。
- S-07：每项批准绑定固定 SQL/参数/数据源/流程版本/权限上下文，变更即失效。
- S-08：数据库网络超时后结果未知，不得对非幂等写操作自动重试。
- S-09：组织隔离在每次资源访问和异步任务执行时重新验证，不只依赖 UI。
- S-10：日志包含足够证据但不默认裸存机密数据，敏感参数加密/脱敏。

## 2. 技术栈与版本管理

| 能力 | 技术建议 | 说明 |
|---|---|---|
| Java 运行时 | Java 21 LTS | 项目统一运行基线，依赖最低版本需实际锁定 |
| 核心框架 | Spring Boot 4.x / Spring Framework 7 | Flowable 8、Spring AI 2 对齐的推荐基线 |
| MCP | Spring AI 2.x MCP WebMVC Starter | Streamable HTTP；工具注册由服务层封装 |
| 工作流 | Flowable Engine 8.0.x | 开源 BPMN 引擎，用户任务、计时器、历史；禁用默认无保护管理接口 |
| BPMN 图形设计 | bpmn-js | 保存 BPMN XML，保留 bpmn.io 水印 |
| SQL 解析 | ShardingSphere SQL Parser | 使用独立 Parser SPI；每库进行覆盖率认证 |
| SQL 语义层 | 自研统一 `SqlSecurityContext` | 不将三方 AST 暴露给规则和审批服务 |
| 规则引擎 | 自研确定性策略引擎 | 显式规则优先级和审计解释，可后续用 DMN 扩展 |
| 认证 | Spring Security OAuth2 Resource Server | 对接 Keycloak 或其他 OIDC；首次安装提供本地管理员 |
| JDBC | HikariCP + 每库官方/兼容驱动 | 只读/写入分开池与凭据 |
| 配置/元数据 | **PostgreSQL 17（确定）** | 单库 `dataguard`；`platform` + `flowable` 双 Schema / 不同账号 |
| 前端 | Vue 3 + TypeScript + Ant Design Vue | BPMN、CodeMirror/Monaco、表格、图表 |
| 可观测性 | OpenTelemetry + Micrometer + Prometheus/Grafana | 审计是业务证据，指标是运行度量 |
| 数据变更 | Flyway（平台 schema） / Flowable 官方升级机制（引擎 schema） | 版本化迁移；Flowable 自有 schema 按其文档升级 |
| 部署 | Docker Compose；Kubernetes Helm（后续） | 自部署，公网入口默认 TLS+认证 |

**版本核实**：Spring AI 2.0（2026-06 发布）与 Spring Boot 4 配套；Flowable 8.0.0 也升级至 Spring Boot 4。文档中版本只是经过确认的基础组合，具体补丁版本、传递依赖及 ShardingSphere 兼容仍须在 CI 的 BOM 中锁定和测试。[R1][R2][R3]

## 3. 总体架构及部署单元

### 3.1 逻辑架构

```text
MCP Clients / AI Agents                 Web Users / DBA
          |                                     |
          v                                     v
  [HTTPS / OAuth2 / OIDC]               [HTTPS / OIDC]
          |                                     |
          v                                     v
+----------------------+       +------------------------------+
| mcp-access-service   |------>| management-api + security    |
| Spring AI MCP        |       | tenant/agent/datasource     |
| tool ACL / quota     |       | policy/audit/admin REST     |
+----------+-----------+       +-------------+----------------+
           |                                   |
           v                                   v
+--------------------------------------------------------------+
| SQL Security Core                                            |
| dialect SPI → AST normalization → permission → policy       |
| risk evaluation → ALLOW / REQUIRE_APPROVAL / DENY            |
+------------+----------------------+--------------------------+
             |                      |
        ALLOW read             REQUIRE_APPROVAL
             |                      |
             v                      v
+---------------------+  +-------------------------------+
| readonly-executor   |  | approval-service + Flowable   |
| read DB credentials |  | BPMN, tasks, timer, history   |
+---------------------+  +---------------+---------------+
                                         |
                              signed/verified task
                                         v
                              +-----------------------+
                              | write-executor        |
                              | isolated credentials  |
                              | idempotent scheduler  |
                              +-----------------------+
                    |                      |
                    v                      v
            MySQL / PostgreSQL / SQL Server / Oracle / ...

Support: product DB + Flowable schema + audit store + Secret provider
Observability: OpenTelemetry / Prometheus / Alerting
```

### 3.2 初始物理部署边界

- **management-service**：模块化单体；组织、用户、Agent、数据源管理、策略引擎、Flowable、后台 REST 和审计业务数据库操作。
- **mcp-service**：对外暴露 `/mcp`，实现令牌验证、Agent/工具授权、请求限流，调用共享安全核心。与管理服务可在同仓库多模块构建。
- **readonly-executor**：可作为受限独立服务或容器运行，部署在仅能访问只读账户的数据网络分区。
- **write-executor**：强制独立进程/容器；独立 Secret 与网络 ACL，定向出网到指定数据库。
- **web-console**：静态 Vue 前端；浏览器不直接连目标数据库或 Flowable 低层 REST。
- **platform-db**：持久化租户、配置、工单关联、执行任务、审计；Flowable 位于同一 PG 数据库的独立 `flowable` Schema，并使用独立账号。

Docker Compose 面向单机试用；生产推荐反向代理 TLS、独立持久卷、受限服务账户、外部 Secret 管理、备份和网络隔离。后续部署支持多副本时，后台任务必须使用可验证的租约和 fencing 机制。

## 4. Maven/前端工程结构

```text
mcp-dataguard/
├── pom.xml                       # BOM 锁定和依赖扫描
├── dataguard-common/             # ID、错误码、审计上下文
├── dataguard-domain/             # 领域实体/状态机/事件
├── dataguard-identity/           # OIDC/机器身份/组织 RBAC
├── dataguard-mcp-server/         # 工具声明、传输、授权
├── dataguard-sql-spi/            # 方言和安全语义接口
├── dataguard-sql-shardingsphere/ # Parser 框架适配
├── dataguard-database-spi/       # 元数据/连接/执行器能力
├── dataguard-plugin-mysql/
├── dataguard-plugin-postgresql/
├── dataguard-policy/             # 规则、风险、版本与模拟
├── dataguard-approval-flowable/  # BPMN/任务/流程版本
├── dataguard-read-executor/
├── dataguard-write-executor/
├── dataguard-audit/              # 日志、追踪、完整性证明
├── dataguard-management-api/     # REST/OpenAPI
├── dataguard-web/                # Vue + BPMN designer
├── dataguard-deploy/             # compose/helm/migrations
├── docs/                         # PRD/ADR/RFC/API/security
├── tests/                        # 对抗测试/容器集成测试
├── LICENSE
├── NOTICE
├── SECURITY.md
└── CONTRIBUTING.md
```

对外 SPI 放稳定包；禁止插件直接操作 Flowable 内部表、跨租户产品表或主进程凭据。

## 5. 多数据库插件与方言适配设计

### 5.1 功能分离

数据库插件由三个独立维度构成：`ConnectionProvider` 管理 JDBC/密钥；`MetadataProvider` 处理 schema/对象权限；`SqlDialectAdapter` 解析、语义及方言风控；`QueryExecutor` 按只读/受控写入能力执行。不要因为某驱动能连接就将整个插件标为“完全支持”。

### 5.2 接口契约（设计伪代码）

```java
public interface DatabaseDialectPlugin {
    String databaseType();
    PluginCapabilities capabilities();
    SchemaSnapshot inspect(AuthorizedDatasource ds, SchemaScope scope);
    SqlSecurityContext analyze(String sql, SchemaSnapshot metadata);
    ExplainAssessment estimate(ChangeRequest request); // optional
}

public record PluginCapabilities(
    boolean metadata, boolean safeRead,
    boolean insert, boolean update, boolean delete,
    boolean transactionalDml, boolean ddl,
    boolean estimateAffectedRows,
    Set<String> certifiedServerVersions) {}

public record SqlSecurityContext(
    String dialect, SqlOperation operation,
    Set<QualifiedObject> referencedObjects,
    Set<QualifiedObject> modifiedObjects,
    boolean multipleStatements,
    boolean hasSideEffects,
    boolean isCompletelyAnalyzed,
    Set<RiskFlag> riskFlags,
    String normalizedFingerprint) {}
```

接口中的 `hasSideEffects`/`isCompletelyAnalyzed` 是平台经过额外检查后的保守结论，不直接假设解析器提供。插件抛出 `UnsupportedSyntax` 或语义不确定时系统默认 `DENY`。

### 5.3 解析器流程

1. 获取由服务端验证的数据源 `dbType` 与版本，禁止 Agent 自选无关方言。
2. 先限制请求字节/字符量、参数数量、多语句、注释与危险编码。
3. ShardingSphere 方言 Parser → 访问器抽取 statement AST 与对象引用。
4. 使用元数据解析 Schema、别名、视图和权限边界，对风险模式做二次验证。
5. 抽象为 `SqlSecurityContext`，标记已覆盖语义与未知区域。
6. 特定数据库增加专有安全规则（PostgreSQL writable CTE、MySQL `SELECT INTO OUTFILE`、SQL Server `SELECT INTO`、ClickHouse mutation 等）。
7. `PolicyEngine` 基于保守语义做 ALLOW/APPROVAL/DENY；未知必 DENY。

ShardingSphere Parser 的能力不能替代完整安全审计。其 5.5.3 官方 SPI 页面列示的解析器与 Visitor 覆盖并非所有数据库一一对等，须逐方言核对。[R4]

### 5.4 兼容和发行门槛

| 数据库 | 解析与元数据 | 只读执行 | 写入执行 | 主要安全关注点 |
|---|---|---|---|---|
| MySQL 8.x | V1.0 认证 | V1.0 | V1.0 指定表 DML | UDF、存储函数、副作用 SELECT、隐式提交、存储过程 |
| PostgreSQL 指定版本 | V1.0 认证 | V1.0 | V1.0 指定表 DML | writable CTE、函数权限、SECURITY DEFINER、COPY、事务语义 |
| MariaDB | 计划适配 | 计划 | 单独认证 | 与 MySQL 差异和版本语法 |
| SQL Server | 后续认证 | 后续认证 | 后续认证 | SELECT INTO、EXEC、MERGE、OUTPUT |
| Oracle | 后续认证 | 后续认证 | 后续认证 | PL/SQL、存储过程、自治事务、驱动许可证 |
| ClickHouse | 后续认证 | 后续认证 | 默认禁用直到专项设计 | ALTER ... DELETE/UPDATE 异步 mutation |
| 国产/湖仓数据库 | 插件计划 | 按库逐项认证 | 默认禁用 | 方言、JDBC、DDL/事务的不同语义 |

每个插件维护 `certification.json`：数据库版本、驱动版本、连接测试、Parser 测试、对象识别、只读对抗测试、变更/事务测试、已知禁用语法和最后认证日期。

## 6. MCP 协议与安全边界

### 6.1 传输与认证

以 Spring AI MCP Server 的 Streamable HTTP 作为首版正式入口（`POST/GET /mcp` 的行为遵循当前 SDK/协议实现），经 TLS 暴露。Spring AI MCP Starter 默认不为 HTTP MCP 端点启用认证，必须用 Spring Security 的 Resource Server 配置验证访问令牌。[R5]

验证 `issuer/audience/expiry/signature/scope/tenant/subject`；使用 OAuth 受保护资源元数据服务发现。机器身份从可信 token claims 映射，若由人类用户授权 Agent 代其发起请求，需同时记录委托关系和授权边界，不允许向内部数据库透传客户端 OAuth token。遵循 MCP 授权规范及资源受众约束。[R6]

### 6.2 工具契约

| 工具 | 请求字段（核心） | 返回字段（核心） | 额外校验 |
|---|---|---|---|
| `list_datasources` | filter/cursor | dataSourceId/type/capabilities/cursor | 可见组织和授权列表 |
| `get_schema` | datasourceId/schema/table/cursor | schemas/tables/columns/cursor | 对象级列可见权限 |
| `execute_readonly` | datasourceId/database/sql/parameters/pageSize | requestId/columns/rows/truncated/duration | 单 SQL + 完整只读分析 + SQL ACL |
| `check_sql` | datasourceId/database/sql/parameters | requestId/operation/risk/decision/policyIds | 不执行，不泄露绕过逻辑 |
| `submit_change` | datasourceId/database/sql/parameters/reason/idempotencyKey | requestId/approvalId/PENDING_APPROVAL | 只能申请可审批 SQL |
| `get_change_status` | approvalId | approvalStatus/executionStatus/summary | 本人、委托人或授权审批人 |

不向默认 Agent 公开批准、用户管理、规则修改、系统命令/外部网络调用等高权限工具。

### 6.3 请求示例（MCP 工具参数层，非原始 JSON-RPC 帧）

```json
{
  "datasourceId": "ds_prod_mysql_01",
  "database": "app",
  "sql": "DELETE FROM orders WHERE id = ?",
  "parameters": [1024],
  "reason": "删除已确认无效订单",
  "idempotencyKey": "b4f5366d-4adf-41c2-9cf9-923bc3ea55b1"
}
```

```json
{
  "requestId": "req_100001",
  "approvalId": "apr_100001",
  "approvalStatus": "PENDING_APPROVAL",
  "executionStatus": "NOT_STARTED",
  "message": "SQL 尚未执行，等待人工审批"
}
```

约定 JSON 错误码如 `UNAUTHENTICATED`、`PERMISSION_DENIED`、`SQL_UNSUPPORTED`、`POLICY_DENIED`、`APPROVAL_REQUIRED`、`APPROVAL_EXPIRED`、`EXECUTION_UNKNOWN`、`RATE_LIMITED`、`INTERNAL_ERROR`。安全服务不能回传数据库真实口令、堆栈或 SQL 解析器细节。

## 7. 身份、组织、授权与数据隔离

### 7.1 身份层

`HumanPrincipal` 来自 Web OIDC/本地认证，`AgentPrincipal` 来自 OAuth 客户端/机器 JWT。`DelegationContext` 记录 AI Agent 被哪个用户/服务触发，以明确发起责任。请求内声明 `tenantId/agentId` 仅供显示，不当作可信授权来源。

### 7.2 权限判定模型

```
EffectivePermission =
  AuthenticatedIdentity
  ∩ OrganizationMembership
  ∩ AgentToolScopes
  ∩ DatasourceBinding
  ∩ ObjectPrivilege
  ∩ SQLPolicy
  ∩ DatabasePhysicalPrivileges
```

任何一层拒绝即失败；安全管理员可创建特定审批例外，但不能覆盖解析不完整、多语句或物理数据库拒绝这类硬拒绝。

### 7.3 多租户隔离

- API/查询条件服务端强制 `organization_id` 范围；资源详情通过 `(organization_id, resource_id)` 双键定位。
- Flowable process business key 和 variables 带组织 ID，并由应用层严格授权；不依赖引擎自带 tenantId 作为完整安全边界。
- 执行器任务携带签名组织上下文，复核资源归属。
- 极高隔离要求下支持客户独立部署；共享数据库逻辑隔离不能替代强网络隔离。

## 8. SQL 策略引擎设计

### 8.1 决策顺序

```text
Authenticated?   no → DENY
Tenant allowed?  no → DENY
Tool allowed?    no → DENY
DB plugin known? no → DENY
SQL completely understood? no → DENY
Hard-deny patterns? yes → DENY
Object permission? no → DENY
Policy matches deny? yes → DENY
Policy matches approval? yes → REQUIRE_APPROVAL
Policy matches allow? yes → ALLOW
Else → DENY
```

策略文件保存为结构化 JSON/数据库记录，包含 `policyId/version/priority/scope/conditions/decision/approvalFlowKey/effectiveAt/createdBy`，禁用客户端提供的审批策略 ID 直接覆盖匹配结果。

### 8.2 示例规则

```json
{
  "id": "policy-prod-dml",
  "version": 3,
  "priority": 200,
  "scope": {"environment": "production", "databaseTypes": ["MYSQL", "POSTGRESQL"]},
  "when": {"operations": ["INSERT", "UPDATE", "DELETE"]},
  "decision": "REQUIRE_APPROVAL",
  "approvalFlowKey": "dml-two-person-approval",
  "guards": {"requireWhereForUpdateDelete": true, "requireFullAnalysis": true}
}
```

### 8.3 SQL 审查防绕过要求

禁用多语句执行、动态 SQL、储存过程或 UDF 未认证调用、系统文件/网络导出、锁定查询和隐式修改路径。`SELECT` 前缀不是只读证明，`EXPLAIN` 估算也不是影响行数保证。行级/列级权限优先数据库原生 RLS/GRANT，应用层结果脱敏作为补充，不把输出掩码误认为防止敏感数据被 SQL 聚合推断的充分防线。

## 9. Flowable BPMN 设计与执行交接

### 9.1 工作流模板

**安全默认流程**：Start → 自动风控确认 → 审批人任务 → 生产环境 DBA/安全二次审核 → 决议落库 → `APPROVED` → 发送执行意图（outbox） → End。拒绝/超时/撤回进入终止分支。动态人员分配必须经过服务端授权与身份有效性检查。

### 9.2 流程边界

Flowable 只负责流程实例、任务、计时器、会签与历史查询，产品数据库是 SQL 内容、审批授权与执行状态的业务事实源。不要直接让 Flowable Service Task 使用 JDBC 写业务数据库，避免引擎重试造成重复 DML。

### 9.3 工单状态机

```text
DRAFT → SUBMITTED → PENDING_APPROVAL ──approve──> APPROVED
                     |                          |
                     ├──reject──> REJECTED      v
                     ├──cancel──> CANCELLED   QUEUED
                     └──expire──> EXPIRED       |
                                                v
                                             EXECUTING
                                          /      |        \
                                       SUCCESS  FAILED    UNKNOWN
```

定义两套状态：`approval_status` 与 `execution_status`；不可把“审批成功”当作“执行成功”。状态转换使用 DB CAS/乐观锁，防止并发审批/执行重复。

### 9.4 审批对象的不可变快照

在提交时保存加密 SQL 文本、参数的规范化序列化及 HMAC/hash、源数据源 ID、数据库/Schema、目标表、组织/Agent、风险标签、策略版本、流程模板版本、有效期和必要的权限快照。批准后再次验证**当前**组织/策略/账号权限；任何安全降级或目标变化均需重新申请。

### 9.5 审批和执行交接事务

1. 审批任务完成时，业务服务在本地事务里更新 `approval` 为 APPROVED、写 `execution_task` 为 QUEUED、写 `outbox_event`。
2. outbox dispatcher 可靠分发“执行任务可领取”提示。事件重复可接受，执行任务必须去重。
3. 写执行器通过数据库原子租约领取任务，复核签名/SQL/版本/有效期、数据库权限和账户白名单。
4. 执行器记录 attempt，并在受控事务中执行；执行结果持久回写审计及状态。
5. 如果“数据库可能已经提交但回执未持久化”，标记 UNKNOWN，不直接重放 SQL；需要 DBA 对账或业务去重机制。

> **精确一次声明禁止**：在目标数据库与管理数据库之间没有分布式事务的前提下，不能凭 `idempotencyKey` 保证任意非幂等 SQL 的端到端 exactly-once。目标是“受控的至多一次自动尝试 + 未知态人工核实”。

### 9.6 行数限制

对支持事务的数据库，允许在合适隔离级别和权限范围内执行后检查 `updateCount`，超限则尝试 rollback；DDL、触发器、外部副作用、隐式提交和某些引擎操作无法保证回滚。对不能可靠限制的变更，应拒绝或要求 DBA 通过外部维护流程处理。

## 10. 执行器详细设计

### 10.1 只读执行器

仅使用只读数据库账号；HikariCP 单源池限制大小，连接/查询超时；单 SQL、参数绑定、数据库驱动 `readOnly` 标志与物理 GRANT 共同保护；结果支持分页或截断，限制最大字节和敏感字段掩码。对不支持安全游标分页的驱动，采用受限返回并明确 `truncated=true`。

### 10.2 写执行器

独立镜像/进程、受限出站网络、单数据源操作白名单、按组织及数据源隔离连接池；仅从内部受保护调度读取已批准任务。凭据来自独立 Secret provider，写权限尽量限制到必要表与 DML；DDL 默认不启用。

### 10.3 执行任务状态

`QUEUED → CLAIMED → EXECUTING → SUCCEEDED/FAILED/UNKNOWN`，`lease_owner/fencing_token/lease_until/attempt_no` 控制并发领取。进入 EXECUTING 后失联，应基于审计和数据库对账判断，不能依靠 lease 到期自动再次执行 SQL。

## 11. 数据模型及表设计

### 11.1 核心表

| 表 | 主键及关键列 | 作用与索引 |
|---|---|---|
| `org` | id, name, state | 组织管理 |
| `app_user` | id, org_id, oidc_sub, state | 人员身份映射 |
| `role`, `user_role` | id, org_id, scope | RBAC |
| `agent` | id, org_id, owner_user_id, status | Agent 资产 |
| `agent_scope` | agent_id, datasource_id, tool, object_pattern | 有效权限 |
| `datasource` | id, org_id, type, environment, secret_ref | 数据源配置 |
| `datasource_capability` | datasource_id, capability, certified_version | 插件能力认证 |
| `sql_policy`, `sql_policy_version` | org_id, id, version, priority, decision | 规则草稿与发布 |
| `sql_request` | id, org_id, agent_id, ds_id, fingerprint | 请求事实源 |
| `sql_approval` | id, request_id, flow_version, process_instance_id | 审批业务单 |
| `sql_approval_action` | approval_id, human_id, action, time | 人员操作历史 |
| `sql_execution_task` | id, approval_id UNIQUE, state, lease, version | 执行授权与幂等 |
| `sql_execution_attempt` | task_id, attempt_no, phase, result | 独立执行轨迹 |
| `sql_audit_event` | id, org_id, request_id, event_type, hash_prev | 审计追加写 |
| `outbox_event` | id, aggregate_id, event_type, dispatched_at | 本地事务事件 |
| `webhook_endpoint`, `webhook_delivery` | id, org_id, event, state | 外部通知 |
| `plugin_registry` | type, version, capabilities, checksum | 插件与认证版本 |

所有对象需要 `created_at`, `updated_at`, `version`，尽可能按 `(org_id, id)` 限定查询和唯一性；流程表由 Flowable 管理，不直接修改 ACT_*。

### 11.2 PostgreSQL 17 DDL（真实基线摘录）

以下摘录自完整 Flyway V1；正式安装必须运行 **整份** `database/postgresql/V1__platform_baseline.sql`，因为执行任务表引用审批表等前置对象。

```sql
CREATE TABLE dg_execution_task (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, approval_id UUID NOT NULL,
 task_state VARCHAR(24) NOT NULL CHECK(task_state IN ('QUEUED','CLAIMED','EXECUTING','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
 payload_hmac CHAR(64) NOT NULL, authorization_digest CHAR(64) NOT NULL,
 authorization_expires_at TIMESTAMPTZ NOT NULL,
 lease_owner VARCHAR(128), lease_until TIMESTAMPTZ,
 fencing_token BIGINT NOT NULL DEFAULT 0 CHECK(fencing_token >= 0), attempt_no INT NOT NULL DEFAULT 0 CHECK(attempt_no >= 0),
 enqueued_at TIMESTAMPTZ NOT NULL DEFAULT now(), started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id), UNIQUE(org_id,approval_id), FOREIGN KEY(org_id,approval_id) REFERENCES dg_sql_approval(org_id,id)
);
CREATE INDEX idx_dg_execution_task_pickup ON dg_execution_task(task_state,lease_until,enqueued_at) WHERE task_state IN ('QUEUED','CLAIMED');
```

SQL 原文及参数使用 envelope encryption 存储；签名 HMAC 必须绑定 SQL 原始字节、参数、Agent、数据源、规则与工作流版本及授权有效期。不能仅凭 SQL 格式化文本或普通散列授权执行。

## 12. REST 管理 API 约定

统一前缀 `/api/v1`，Web/Admin 与 MCP 安全边界隔离，全部提供 OpenAPI 文档；API 授权不依赖按钮是否隐藏。

| 方法与路径 | 用途 | 角色 |
|---|---|---|
| GET/POST `/organizations` | 组织列表及创建 | 平台管理员 |
| GET/POST `/agents` | Agent 资产与注册 | 组织管理员 |
| POST `/agents/{id}/revoke` | 撤销机器身份 | 组织管理员 |
| GET/POST `/datasources` | 数据源配置 | 数据源负责人 |
| POST `/datasources/{id}/test` | 连接与权限检测 | 数据源负责人 |
| GET/POST `/policies` | 安全策略列表/草稿 | 安全管理员 |
| POST `/policies/{id}/simulate` | 策略模拟 | 安全管理员 |
| POST `/policies/{id}/publish` | 发布不可变规则版本 | 安全管理员 |
| GET/POST `/workflows` | BPMN 模板管理 | 流程管理员 |
| POST `/workflows/{id}/publish` | 流程校验与版本发布 | 流程管理员 |
| GET `/approvals` | 工单查询 | 申请人/审批人 |
| POST `/approvals/{id}/actions` | approve/reject/delegate | 有权处理任务的人员 |
| GET `/audit/sql` | SQL 事件检索 | 授权审计人员 |
| GET `/metrics/summary` | 业务指标聚合 | 组织审计/运维 |
| POST `/webhooks` | 通知端点管理 | 组织管理员 |

写类 REST API 支持 `Idempotency-Key`，统一返回 `requestId`，冲突响应 `409`，权限失败 `403`，不存在或不可见统一资源错误；审批接口显式校验 Flowable task assignee/candidate group 的业务身份。

## 13. 审计与可观测性实现

### 13.1 审计分层

- **请求日志**：MCP tool name、身份上下文、数据源与 SQL 指纹、策略决策、请求/响应状态。
- **业务审计**：工单内容摘要、流程版本、审批意见、执行票据和最终状态。
- **基础设施指标**：QPS、延迟、连接池、线程、队列堆积、Flowable job backlog、失败告警。

所有层共享 `requestId/traceId/orgId/agentId/approvalId/executionId` 以跨系统追踪。日志异步上报失败不应意外允许危险 SQL；关键审计保存不可用时，写入请求默认阻断或转安全持久 spool 后再继续（按批准的灾难策略）。

### 13.2 日志机密性与完整性

保留原文 SQL 必须有严格权限及加密；默认审计 UI 显示脱敏版本，参数只显示类型和可安全公开内容。为审核保留受控明文查看能力，并审计谁查看过明文。哈希链加周期性外部不可变归档，实现篡改迹象验证而非绝对防篡改的营销表述。

### 13.3 指标示例

`mcp_requests_total{tool,status}`、`sql_policy_decisions_total{decision,dialect}`、`sql_query_duration_seconds`、`approval_pending_total`、`approval_duration_seconds`、`executor_unknown_total`、`datasource_pool_active`，不得将原始 SQL 或密钥作为 metrics 标签。

## 14. 安全威胁模型与防御

| 威胁 | 攻击路径 | 设计控制 |
|---|---|---|
| Prompt injection | Agent 被诱导执行写入或导出 | 强制工具授权、只读账号、SQL 解析和审批 |
| SQL 解析绕过 | 多语句、函数、副作用 CTE | 方言安全测试、未知拒绝、DB 最小权限 |
| IDOR / 租户串访问 | 猜测资源 ID | 服务端组织双键、全域范围复核 |
| Token 假冒/透传 | 错误受众、未校验 issuer | OAuth Resource Server、aud/iss、禁透传 |
| 审批绕过 | 伪造审批完成/替换 SQL | Flowable 与业务状态双校验，绑定摘要及权限 |
| 重放攻击 | webhook/队列事件重复 | 唯一任务、租约/fencing、UNKNOWN 人工对账 |
| 凭据泄露 | Web/API 访问写凭据 | 进程/网络/Secret 独立隔离 |
| 凭据滥用 | 使用超权限写账号 | 最小 GRANT、只允许指定表和变更类型 |
| 审计泄漏 | 日志带敏感数据 | 字段脱敏、加密、权限、导出审计 |
| SSRF | 自由配置 JDBC 地址 | 禁止实例元数据/回环等目标、网络出站白名单 |
| 插件供应链 | 第三方驱动/插件恶意依赖 | 签名/校验、SBOM、最小权限隔离及代码审查 |
| 流程表达式滥用 | 恶意 BPMN 表达式或脚本节点 | 仅允许批准的 BPMN 元素、表达式白名单和发布审核 |

特别提醒：数据库驱动某些 JDBC 参数可以启用文件访问或副作用，连接字符串必须由平台生成并采用允许列表，不能允许 Agent 提供任意 JDBC URL。

## 15. 部署、数据库与配置安全

### 15.1 最小 Compose 拓扑

```text
reverse-proxy (TLS)
  ├── web-console
  ├── mcp-service
  └── management-service
platform-db ── management-service / Flowable
readonly-executor ── readonly network + business db SELECT account
write-executor ── isolated network + narrowly scoped DML account
optional: Keycloak, Redis, Prometheus, Grafana, object storage, Vault
```

发布 `compose.yaml`、`.env.example`，默认无开发口令，无未受保护的数据库服务公网端口。安装向导只在首次启动有效，初始化管理员账户后自动关闭；生产配置须支持单独信任根、证书轮换和出站访问控制。

### 15.2 数据库和流程 schema

平台业务表只位于 `dataguard.platform`，Flowable runtime/history 表只位于 `dataguard.flowable`；禁止业务代码直接写 Flowable 内部表。修改 BPMN 模板与执行数据必须走应用 API。生产数据库执行账号与平台元数据账号分离，业务 DB 断连不能损毁审批工单。

### 15.3 升级恢复

迁移脚本具备版本检查、前置备份、可演练恢复；流程实例使用历史 BPMN 发布版本运行；镜像采用不可变 tag + digest；从公开源码可以构建所有必需组件。提供备份与恢复演练脚本，包含未知态执行任务的人工核查流程。

## 16. 测试与质量保证

### 16.1 测试层级

- 单测：Parser visitor、策略优先级、租户过滤、审批状态机、票据完整性、JSON Schema。
- 集成：Testcontainers MySQL/PostgreSQL、Flowable 流程、OAuth/OIDC、只读/写入权限隔离。
- 协议兼容：MCP Inspector 及至少两个实际 Client 的工具发现/调用/错误处理测试。
- SQL 对抗：多语句、注释、编码、视图、子查询、函数、CTE、跨 schema、DDL、事务、权限、性能大查询。
- 故障注入：审批引擎宕机、执行器崩溃、数据库已提交但回执丢失、事件重复、网络分区。
- 性能：P95 网关附加延迟、持久化审批吞吐、并发查询、连接池；记录测试硬件及数据集。
- 安全：依赖扫描、SAST、DAST、秘密扫描、越权/IDOR、SSRF、第三方许可证扫描。
- 端到端：Agent submit_change → Flowable 两级审批 → 执行器写入 → MCP status → 审计链路闭合。

### 16.2 否决型用例

任何误把写入语句归为 ALLOW 只读、工单自批/越权、异常重试引起重复变更、SQL 快照可篡改、未认证 MCP 可运行 SQL、执行器通过 Web 获得写凭据，均阻断发行。

## 17. 交付与开发工作分解

| 阶段 | 任务包 | 输出物 |
|---|---|---|
| 架构基线 | Monorepo/BOM、ADR、模型、CI、安全基线 | 可重复构建、静态审查 |
| V0.1 | OIDC + Agent ACL、MCP Tools、MySQL/PG 安全只读、SQL SPI | 只读闭环演示 |
| V0.3 | 规则管理/模拟、SQL 事件审计、独立只读执行器 | 可测试安全策略 |
| V0.5 | Flowable BPMN、审批工作台、签名执行任务、写执行器 | 写操作审批闭环 |
| V0.7 | Web 全部主要页面、通知、指标/告警、数据库认证测试 | 开放 Beta |
| V1.0 | 开源文档、Compose、备份升级、SBOM、CI/安全测试、MCP 兼容认证 | 可公开自部署正式版 |
| 后续 | 扩充数据库插件/Helm/高级策略 | 分版本迭代 |

## 18. 开源工程治理与许可证

- 拟对项目源码采用 Apache License 2.0，仓库发布 `LICENSE/NOTICE`，保留贡献者著作权与所需 NOTICE。**不**包含 License Key、激活、商业功能分级或收费接口。
- Flowable 引擎的开源部分采用 Apache-2.0，不能混淆其商业产品模块；bpmn-js 使用 bpmn.io 专有许可文本，要求保留可见水印及许可证；数据库驱动逐一审查再发行/下载方式，尤其 Oracle 驱动。
- 提供 `SECURITY.md` 私密漏洞上报途径、`CONTRIBUTING.md`、开发环境脚本、自动化测试和 SBOM；允许外部贡献数据库插件，但插件未通过安全兼容测试不得标记为认证支持。
- 不向社区承诺维护期限、SLA 或人工审批服务；提供公开路线图和版本变更策略。

## 19. 架构决策记录（ADR）

| ADR | 决策 | 理由 | 替代方案 |
|---|---|---|---|
| ADR-001 | Spring AI MCP 作为首版 MCP 服务 | 集成 Java 生态且可明确鉴权 | ShenYu/APISIX/Higress 放在可选前置层 |
| ADR-002 | Flowable 8 工作流 | 人工审批、BPMN、多实例、历史 | 简单状态机无法长期覆盖复杂流程 |
| ADR-003 | ShardingSphere Parser 通过 SPI 接入 | 以数据库方言与安全对象抽取为先 | Calcite 留给未来语义/跨库查询，不与安全引擎耦合 |
| ADR-004 | 独立 SQL Security Context | 降低解析器依赖，支持逐库策略 | 直接在各 AST 类型上写规则维护性差 |
| ADR-005 | 独立写入执行器 | 最小权限与秘密隔离 | Web/MCP 直接持有写账户风险高 |
| ADR-006 | Flowable 不直接提交 SQL | 防止 job 重试重复副作用 | Service Task 直接执行 DML 不可接受 |
| ADR-007 | 开放完整源代码，无 License 功能 | 让开发者及企业无门槛使用 | 免费版/付费版不是产品目标 |

## 20. 开发前需要确认的清单

- 锁定 **Spring Boot 4 + Spring AI 2 + Flowable 8** 的具体补丁版本和依赖兼容 CI。
- **已决定**：平台管理库 PostgreSQL 17；目标业务数据库仍按数据库插件 SPI 支持 MySQL/PostgreSQL 等；不计划 V1 双平台库迁移。
- 明确 MySQL/PostgreSQL 支持的精确服务端版本、驱动版本及安全方言用例。
- 选定 OAuth 身份提供方：Keycloak 作可选部署，本地用户仅用于控制台引导；远程 MCP 生产使用安全 OAuth/OIDC。
- 明确第一版审批流程的同意、拒绝、会签、超时、撤销、转交权限和策略。
- 明确审计参数加密策略、密钥轮换与备份、保留期、导出审核。
- 完成 Compose 自部署网络拓扑和真实目标 DB 最小权限脚本。
- 正式开源前完成许可证扫描、商标/项目命名冲突检查及 `SECURITY.md`。

## 21. 参考资料

- [R1] Spring AI 2.0 GA：https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
- [R2] Flowable Engine Releases：https://github.com/flowable/flowable-engine/releases
- [R3] Flowable Engine README / Apache-2.0：https://github.com/flowable/flowable-engine
- [R4] ShardingSphere Parser 5.5.3 SPI：https://shardingsphere.apache.org/document/5.5.3/en/dev-manual/sql-parser/
- [R5] Spring AI MCP Boot Starter 及安全提示：https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
- [R6] MCP 2026-07-28 授权规范：https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2026-07-28/basic/authorization/index.mdx
- [R7] Flowable BPMN 用户任务和会签：https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs/
- [R8] bpmn.io License：https://bpmn.io/license/
- [R9] OSI Approved Licenses：https://opensource.org/licenses

> **特别说明**：这是可供开源研发执行的技术设计，而非已完成架构或现成实现。数据库安全由多层机制共同保证；任何单独的 AST、Flowable 审批或 JWT 都不能被当作充分安全证明。生产上线须完成本技术文档及 PRD 中的否决型安全测试。