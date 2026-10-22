# MCP DataGuard

> **dev20261022 · v0.5 开发分支**。已包含可构建的 Spring Boot / Flowable / Vue3 基础工程，但尚不是可供生产使用的 SQL 安全网关。写入与读取业务数据库的 MCP 工具均保持禁用。

MCP DataGuard 是面向 AI Agent 的完全开源数据库安全访问与管理平台，目标是统一管理 Agent、数据源、SQL 安全规则、Flowable 人工审批、执行与审计。

## 技术栈

- Java 21 + Spring Boot 4.0.8 + Spring AI MCP Server 2.0.1。
- Flowable 8.0.0，两级审批 BPMN 示例；尚未接通可信审批工单。
- PostgreSQL 17 单个 `dataguard` 数据库：`platform` 和 `flowable` 两个 Schema、独立账号。
- ShardingSphere 5.5.3 依赖已列入项目；AST 安全语义分析尚未认证。
- Vue3、Ant Design Vue、Vite、OIDC Authorization Code + PKCE。

## 已实现的代码

- 平台管理库 44 张业务表的 Flyway V1 迁移；CI 在实际 PostgreSQL 17 中成功运行。
- OIDC JWT issuer/audience 基础校验；注册 Agent/User 的可信身份摘要匹配，验证组织及身份状态。
- Agent 专属 MCP `list_datasources`：根据组织、有效 Agent 和显式库权限过滤，只返回授权的非敏感数据源信息。
- 管理 REST 已实现当前用户、Agent、数据源只读查询，以及 Agent/数据源新增、修改、撤销/停用。后端按可信组织身份和角色权限校验，并进行输入校验及乐观锁控制。
- 六个 MCP 工具已经声明，除了 `list_datasources`，其余五个因未获安全认证而拒绝执行。
- SQL 简单结构预检查仅用于拒绝，不会因为词法匹配就授予 SQL 执行权限。
- Vue3 管理台已接入 OIDC 登录、Agent 和数据源的增删改入口（仅撤销/停用，不支持实际删除），并提供基础表单。其他页面明确显示开发中。
- GitHub Actions 验证 Maven 单元测试、Vue/TypeScript 构建、实际 PostgreSQL 17 DDL 和 MCP JSON 契约。

## 快速开始（开发环境）

依赖 JDK 21、Maven 3.9+、Node 22+、Docker Compose，以及已正确配置的 OIDC Provider。

```bash
cp deploy/.env.example deploy/.env
# 请编辑密码、数据库连接变量和 OIDC issuer/audience；不要提交 secrets
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d
mvn -B -ntp verify

# 在已导出 deploy/.env 中的环境变量后运行 Spring 服务
mvn -pl dataguard-server -am install
mvn -pl dataguard-server spring-boot:run

# 另一个终端运行 Vue3
cd dataguard-web
cp .env.example .env.local
# 编辑 VITE_OIDC_* 与身份提供商回调设置
npm install
npm run dev
```

OIDC 身份必须先在平台数据库中登记，首次部署可使用 [离线管理员初始化工具](docs/BOOTSTRAP_ADMIN.md) 创建首个组织和 OIDC 管理员；工具不提供匿名 Web 入口。

查看页面要求 `agent:read` / `datasource:read`，创建或修改分别要求 `agent:write` / `datasource:write`。新增 Agent 和数据源均默认禁用，当前尚未实现可信 Agent 机器身份开通以及数据库连接认证。不能通过浏览器传入自定义租户 ID 进行越权查询。

## 本轮新增：身份管理与数据源凭据（开发中）

- `GET/POST /api/v1/admin/users` 和 `GET/POST /api/v1/admin/roles`：组织管理员登记 OIDC 人员与创建最小权限角色。
- `POST/DELETE /api/v1/admin/users/{userId}/roles/{roleId}`：仅能委派非系统角色。
- `POST /api/v1/agents/{agentId}/enroll`：绑定机器身份，验证 issuer、audience、client_id（JWT azp）且拒绝复用人员身份。
- `POST/GET /api/v1/agents/{agentId}/datasource-permissions`：仅授予数据源发现 `list_datasources`；SQL 工具仍被禁用。
- `PUT/GET /api/v1/datasources/{datasourceId}/read-secret`：管理只读 ENV 凭据引用，数据库中不保存明文密码。
- `POST /api/v1/datasources/{datasourceId}/connection-test`：管理员执行服务器端白名单、TLS 身份校验及 SELECT 1 探测；成功也不授予 SQL 执行权限。

参见 [身份与凭据实施说明](docs/IDENTITY_AND_CREDENTIALS.md)。

## 仍需研发的功能

- 后台新增/修改 Agent、数据源、库表授权、用户与策略；凭据 Secret 安全轮换。
- ShardingSphere 方言 AST、SQL 副作用分析、字段级权限、读执行器与结果脱敏。
- Flowable 审批实例 API、任务分配、自批防护、审批快照、可信状态回写。
- 完全独立的写执行器、幂等任务、故障后 `UNKNOWN` 对账机制。
- 完整 SQL 审计链、实时监控、策略编辑器、可视化 BPMN 流程管理、稳定安装升级和兼容性认证。

**生产数据库 SQL 操作默认拒绝，不要把当前工程视为完整产品。**

详细情况参见 [实施状态](docs/IMPLEMENTATION_STATUS.md)、[PRD](docs/PRD.md)、[技术设计](docs/TECHNICAL_DESIGN.md) 和 [研发任务](docs/DEVELOPMENT_BACKLOG.md)。

项目坚持全功能开源、无商业 License。第三方数据库驱动许可证需要逐项审查。
