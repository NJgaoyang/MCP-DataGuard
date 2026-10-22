# 身份、角色、Agent 和数据源凭据开发记录

开发版本：`dev20261022`，2026-10-22。所有功能必须登录 OIDC 且在平台数据库有有效组织身份；不能使用请求参数伪造租户。

## 已实现接口

| API | 要求权限 | 功能 |
|---|---|---|
| GET /api/v1/admin/users | identity:admin | 当前组织用户 |
| POST /api/v1/admin/users | identity:admin | 登记现有 OIDC subject |
| GET /api/v1/admin/roles | identity:admin | 组织角色列表 |
| POST /api/v1/admin/roles | identity:admin | 新建非系统角色，禁止任意权限字符串 |
| POST /api/v1/admin/users/{userId}/roles/{roleId} | identity:admin | 分配非系统角色 |
| DELETE /api/v1/admin/users/{userId}/roles/{roleId} | identity:admin | 撤销非系统角色 |
| POST /api/v1/agents/{agentId}/enroll | agent:identity:write | 绑定独立 IdP 机器身份；仅可绑定 DISABLED Agent |
| POST /api/v1/agents/{agentId}/datasource-permissions | agent:permission:write | 仅授权 list_datasources |
| GET /api/v1/agents/{agentId}/datasource-permissions | agent:permission:write | 查看数据源发现授权 |
| PUT /api/v1/datasources/{datasourceId}/read-secret | datasource:secret:write | 保存服务端 ENV 引用 |
| GET /api/v1/datasources/{datasourceId}/read-secret | datasource:secret:write | 读取 ENV 引用，不返回密钥值 |
| POST /api/v1/datasources/{datasourceId}/connection-test | datasource:test | 白名单主机 + TLS 验证 + 参数化凭据的 SELECT 1 测试 |

这些权限由 Flyway V2 仅赋予系统 org-admin 角色。普通自定义角色只能从四项基础管理权限中选择：`agent:read`、`agent:write`、`datasource:read`、`datasource:write`。敏感管理权限不能委派给自定义角色。

Agent 登记需要 issuer 与资源服务器完全一致，audience 与平台配置一致，同时 JWT 的 `azp` 必须匹配登记的客户端 ID；人员 OIDC 主体不能登记为 Agent。为真实 Agent 颁发凭据和 OAuth 客户端由 Keycloak/IdP 负责，平台不生成该机器身份的明文私钥。

## ENV 凭据

REST 请求只接收如 `DG_TARGET_REPORTING_READ` 的凭据前缀。运维操作员必须在启动网关进程之前，**通过受保护的服务器端环境**配置：

```text
DG_TARGET_REPORTING_READ_USER=<database-readonly-username>
DG_TARGET_REPORTING_READ_PASSWORD=<database-readonly-password>
DG_CONNECT_TEST_ALLOW_HOSTS=reporting.internal.example
```

在 Docker Compose 部署中，以上两项放在本地 `deploy/.env.target`，它仅作为 gateway 的可选私有 env_file 装载；不要把凭据写入 `deploy/.env.dev` 后提交。可从 `deploy/.env.target.example` 复制并设置文件权限为 600。调整 env_file 后应重建/重启 gateway 容器以注入新环境值。

两项实际密码值不得进入 Web 表单、SQL migration、Git、审计日志或普通日志。

只有数据源处于 DISABLED 时允许配置/更换 READ 凭据引用。目标主机必须在运维白名单中；测试函数仅支持 MySQL 和 PostgreSQL，强制 SSL 主机身份校验，并设置连接和查询超时。SQL 探测固定为 SELECT 1。网关返回简洁状态，不向调用方暴露 JDBC 异常细节或密码。

**注意：此功能验证网络和 TLS 通信，不证明物理数据库账号严格只读，不授予 Agent SQL 查询。** 测试环境仍须准备一个带合法证书的目标数据库才可完成端到端连接验收。

## 剩余四阶段

1. 管理闭环：OIDC 浏览器登录验收、全套身份撤销、秘密管理的 Vault/KMS 接入、真实数据库连接测试及 Vue 表单验收。
2. MCP 只读闭环：可靠方言 AST 与副作用分析、对象/字段授权、数据库物理只读账户、严格结果脱敏和额度限制。
3. 审批闭环：Flowable 任务分配、双人审批、自批禁止、审批快照不可篡改、超时/撤回/驳回流程。
4. 安全写执行闭环：与 MCP 进程分离的写凭据、可靠调度、幂等/UNKNOWN 状态和故障对账。
5. 审计监控：完整事件链、脱敏审计、报警、负载与恢复测试。

**目前是开发阶段，未通过审批与执行安全验收前不可允许 SQL 数据变更。**
