# MCP DataGuard 实施状态（2026-10-22）

分支：`dev20261022` · 开源 v0.5 开发版 · **非生产就绪**。

## 已完成

- PostgreSQL 17 `platform`（44 业务表）和 `flowable` Schema；Flyway V1/V2/V3。
- Java 21 / Spring Boot 4 统一网关，Spring AI MCP Server 注册 6 个工具。
- Vue3 / Ant Design Vue 管理台；Caddy 公网 HTTPS。
- **内置认证取代 Keycloak/OIDC**：用户密码 BCrypt、随机不透明会话 token 的服务端摘要、登出撤销。
- 首次登录强制复杂密码修改；后端禁止改密前访问业务接口。
- 连续失败账号锁定、用户/组织状态校验、RBAC 权限验证。
- Agent 独立不透明 Token 签发与撤销；权限在 DataGuard 内管理。
- Agent 与数据源管理、数据源连接测试与环境凭据引用、基础审计事件。
- MySQL/PostgreSQL SQL 方言解析、严格受限的单表 SELECT 结构预检与字段授权检查；**仅用于拒绝，不允许直接执行 SQL**。
- CI 后端、前端、PG 迁移、Compose 与接口契约检查。

## 当前待验收问题

- 用户曾报告首次改密 `POST /api/v1/auth/password` 返回 401。最新分支已包含区分当前密码错误与无效会话的修复，但仍须核对服务器网关镜像和完成真实端到端验证。
- 测试机历史上使用源码文件同步方式部署，Git HEAD 与实际文件可能不一致。
- 多次截图包含测试凭据，所有已曝光密码须更换。
- 旧 Keycloak 运行资产的最终清理需待备份和新认证验收完成后进行。

## 仍未完成

- 完整安全登录审计、外部限流、成熟账户恢复机制。
- 浏览器 HttpOnly Cookie 与 CSRF 防御（当前前端令牌仅保存在内存）。
- 物理只读 SQL 执行器、字段脱敏、限时限量、结果审计、真实数据库端到端测试。
- Flowable 审批业务闭环、写入执行器、UNKNOWN 对账和安全监控。

**SQL 真实执行开关必须继续保持关闭。**
