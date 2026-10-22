# MCP DataGuard · 开源项目交付与接手指南

更新时间：2026-10-22。分支：`dev20261022`。

## 项目用途

面向 AI Agent 的数据库安全访问网关。目标是验证 Agent 身份、数据库权限、
SQL 策略、审批与审计后，才可访问真实数据库。当前处于功能开发和安全封闭阶段。

## 技术架构

| 组件 | 技术 | 说明 |
|---|---|---|
| 管理端 | Vue3 / Vite / Ant Design Vue | 内置账号登录和用户/Agent/数据源管理 |
| API/MCP | Java 21 / Spring Boot 4 / Spring AI | REST + MCP 工具、RBAC、会话与策略预检 |
| 数据库 | PostgreSQL 17 | `platform` 和 `flowable` Schema |
| 审批 | Flowable 8 | 引擎已部署，业务流程尚未完整 |
| 入口 | Caddy + Nginx | HTTPS 代理、静态界面 |
| 认证 | MCP DataGuard 原生 | BCrypt + 256 位随机 opaque token，摘要持久化 |

默认不部署 Keycloak，也不要求 OIDC 配置。

## 项目路径

- `dataguard-server/`：网关、REST/MCP 与数据库迁移。
- `dataguard-sql-core/`：严格 SQL 结构校验与方言解析。
- `dataguard-web/`：后台 UI。
- `dataguard-workflow/`：Flowable 服务。
- `deploy/`：Compose、Dockerfile、Caddy/Nginx 配置。
- `docs/NATIVE_AUTH.md`：管理员初始化、用户与 Agent 认证流程。
- `docs/IMPLEMENTATION_STATUS.md`：真实实施状态。
- `docs/DEPLOY_STATUS_TEST_SERVER.md`：测试机部署和待验收问题。

## 本地构建与测试

```bash
mvn -B -ntp verify
cd dataguard-web && npm install --no-audit --no-fund && npm run build
```

Docker Compose 配置请参阅根目录 README 和 `deploy/.env.*.example`。
私有 `.env.*` 文件、JWT/Token、口令、数据库 dump 不得提交。

## 首次管理员认证

首次安装需要运维人员在服务器本机按
`docs/NATIVE_AUTH.md` 离线初始化管理员临时密码；
系统不会内置 `admin/123456` 默认凭据。

`POST /api/v1/auth/login` 获取短期 bearer token；
首次 `mustChangePassword=true` 时仅允许调用改密和退出接口。
`POST /api/v1/auth/password` 成功后撤销全部旧会话，必须重新登录。
管理员在后台创建用户并授予角色；Agent 用独立 Token，不复用人的密码。

## 不得忽略的未关闭问题

- 首次改密曾报 HTTP 401；需要依据最新源代码**重建运行镜像并端到端验证**，不能只依赖 GitHub CI。
- 不能把登录页面的 200 或匿名 API 401 作为成功改密的证明。
- SQL AST 语法校验不等于完整安全审查，不能凭预检通过解锁 `execute_readonly`。
- 历史密码曾被截图公开，应轮换并清理不必要的本地复制件。

## 发布前核对

1. 比较 GitHub HEAD、部署源码 SHA、Docker 镜像与迁移版本。
2. 在真实 PostgreSQL 测试组织中完成登录 → 首次改密 → 新密码登录 → 退出全链路。
3. 校验用户/Agent 授权及审计，不打印明文密码和 Token。
4. 验证 HTTPS、网关健康和备份/回滚机制。
5. 保持数据库 SQL 执行禁用，直到隔离执行、脱敏、审计、审批完整验收。

交付结论：**基础平台与原生认证开发已完成主要骨架，但改密闭环和数据库执行安全仍需验收，不应宣称生产可用。**
