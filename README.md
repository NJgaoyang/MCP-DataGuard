# MCP DataGuard · MySQL 企业单体版

> **当前默认架构（`dev202610241`）**：Java 21 / Spring Boot 4 单体应用、MySQL 8.4、内嵌 Flowable、企业内部多用户、表级正向白名单、操作审计和非 Docker 的 JAR + systemd 部署。**无 PostgreSQL 和多组织运行依赖**；旧版 PostgreSQL 相关代码仅保留为未参与默认构建的历史目录。

## 快速定位

- 新版后端：`dataguard-standalone/`，默认根 Maven Reactor **只编译此模块**。
- MySQL Flyway 基础表：`dataguard-standalone/src/main/resources/db/migration/V1__mysql_internal_baseline.sql`。
- 入口：`io.github.mcpdataguard.standalone.DataGuardApplication`。
- 新版内置管理页面：`dataguard-standalone/src/main/resources/static/`。
- 安装与迁移说明：[MySQL 单体版手册](docs/STANDALONE_MYSQL.md)。
- 持续集成：[MySQL monolith acceptance](.github/workflows/standalone-mysql.yml)。

全新空库第一次运行会生成 `admin/123456`（仅建议本机初始化），账号密码以 BCrypt 存储，首次登录必须改为至少 12 位复杂密码。**不会在重启时重置密码**。生产必须使用 TLS 入口并确认没有暴露初始化账号。

## 已实现与限制

当前实现 MySQL 元数据、内部用户和审批角色、内嵌 Flowable 指定用户任务、数据源配置、表白名单、审批前置读取、默认全字段 REDACTED 脱敏、操作审计和独立非 Docker 运行方式。

**注意**：这是全新 MySQL 基线而非旧版所有能力已无损迁移。旧 PostgreSQL Gateway/Worker、Agent MCP Token、生产级可选字段脱敏与旧数据迁移尚未完成新架构替换；不得将旧生产门禁或 PG 集成测试结果视作 MySQL 版本验收。当前生产状态 NO-GO。

---

## 历史版本说明（仅 dev20261022 PostgreSQL 架构参考）

# MCP DataGuard

> **dev20261022 · v0.5 开发版**。核心安全功能仍在开发中；SQL 读执行、写入执行默认禁用。

面向 AI Agent 的开源数据库安全访问平台，提供 Agent、数据源、
SQL 权限预检、工作流审批和审计管理。

## 默认架构（不使用 Keycloak）

- Java 21 / Spring Boot 4、Spring AI MCP Server、ShardingSphere SQL Parser。
- PostgreSQL 17，`platform` 和 `flowable` Schema。
- Vue3 / Ant Design Vue / Vite，直接使用**内置账号密码**登录。
- BCrypt 密码摘要，随机 256-bit opaque bearer token；服务端会话摘要和撤销。
- 首次登录强制修改复杂密码（至少 12 位，含大小写、数字、特殊字符）。
- Agent 使用独立 API Token，可轮换、撤销、设置到期。
- Flowable 8 审批引擎，可选服务，尚未接通安全写执行。

**不需要 OIDC Provider、Keycloak、Issuer/JWKS、回调 URI。**
前端令牌仅保存于页面内存，页面刷新后需要重新登录。

## HTTPS 测试部署

复制 `deploy/.env.dev.example` 到 `deploy/.env.dev`，
`deploy/.env.public.example` 到 `deploy/.env.public`，
修改密码及公网地址，设置文件权限 600 后执行：

```bash
bash bin/start-public-test.sh
```

新安装会执行 Flyway V1-V3，之后**必须在服务器本机离线初始化第一个管理员密码**；
没有匿名 Web 初始化接口，也没有开源代码中写死的默认密码。

请阅读 [内置认证与管理员初始化](docs/NATIVE_AUTH.md)。

默认测试组织编码 `dataguard`，管理账户 `admin`；
管理员初始化成功后访问部署站点直接登录，再首次修改密码。
其他人员账号由管理员在管理后台创建并分配角色。

## 当前实现

- 组织隔离、服务端 RBAC、用户与角色的管理接口。
- Agent 创建、编辑、撤销和随机 Agent 访问令牌签发。
- 数据源配置、受控连接测试与安全凭据环境引用。
- SQL 方言语法解析、有限单表 SELECT 结构限制和字段级权限预检。
- 6 个 MCP 工具契约，只有经授权的数据源发现已具备受控读取能力。
- SQL 真实执行、SQL 写入及未经认证的查询仍然拒绝。

## 安全注意事项

认证完成不等于 SQL 安全闭环。实际读执行器、物理只读数据库账户、
字段脱敏、审批执行、写入任务及完整审计体系仍需研发与安全测试。
请勿直接连接生产数据库进行真实 SQL 操作。

项目坚持完整开源，无商业 License。数据库驱动许可证需独立审查。

## 生产发布资格与分阶段启用

常规构建 CI 可能条件跳过需要真实数据库的用例；完整安全验收以 `.github/workflows/mandatory-security-gates.yml` 的 `isolated-security` 为准，必须无跳过。所有生产执行功能继续默认关闭。参见 [上线执行与证据手册](docs/PRODUCTION_ACCEPTANCE_EXECUTION.md) 和 [阶段策略检查](bin/verify-staged-rollout.sh)。

**仓库代码通过 CI 不代表正式生产已获授权**；真实业务身份、正式数据库、告警、密钥、恢复演练、压测、安全签署和 GitHub 分支保护仍需独立现场验收。

### 最新上线工程状态（2026-10-24）

生产安全候选在 [PR #2](https://github.com/NJgaoyang/MCP-DataGuard/pull/2) 中，包含隔离安全强制 CI、独立不可变镜像的生产 Compose、受信 PostgreSQL CA、文件密钥、签名发布和运行态检查、CVEs/SBOM、只读负载测试、审计外部摘要发送。**代码已提交不等于生产已验收**。详见 [实施状态](docs/IMPLEMENTATION_STATUS.md)、[生产部署](docs/PRODUCTION_DEPLOYMENT.md) 和 [上线验收](docs/PRODUCTION_ACCEPTANCE_EXECUTION.md)。
