# MCP DataGuard 当前实施状态

开发分支：`dev20261022` · v0.5 · 非生产版。

## 已完成并纳入持续构建

- Java/Spring Boot/Flowable 基础服务与 OIDC JWT 资源服务器。
- PostgreSQL 17 单库双 Schema 的 44 张表 V1 迁移，PostgreSQL 实例 CI 验证。
- 用户和 Agent 可信 OIDC 身份映射；有效状态与组织隔离。
- REST 个人信息、Agent 与数据源只读列表，严格角色权限检查。
- MCP 工具 `list_datasources` 具有真实平台库查询与 Agent 授权过滤；不返回连接密钥或主机。
- MCP 其余五工具仍为受控拒绝。
- SQL 词法预检查只用于拒绝，不会授予 SELECT 执行权限。
- Vue3 管理台 OIDC 登录与只读列表，Node CI 构建。
- GitHub Actions 后端、前端、真实 PG SQL 初始化、MCP JSON 契约检查。

## 未完成或尚未通过验证

- Agent/数据源 Web 创建修改、OAuth 身份登记向导、统一 RBAC 管理。
- MySQL/PG 目标数据库连接器与真实安全 SELECT、元数据枚举。
- ShardingSphere AST 安全认证、策略匹配与敏感字段脱敏。
- Flowable 审批单与任务业务接口、审批人授权回避。
- 安全写执行器、非幂等重试保护与 UNKNOWN 对账。
- 真实 SQL 审计、告警、性能压测、备份恢复与生产安全测试。

**禁止将 SQL 词法结构检查作为允许执行的依据。业务 SQL 执行未开放。**
