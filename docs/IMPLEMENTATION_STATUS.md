# MCP DataGuard 当前实施状态

开发分支：`dev20261022` · v0.5 · 非生产版。

## 已完成并纳入持续构建

- Java/Spring Boot/Flowable 基础服务与 OIDC JWT 资源服务器。
- PostgreSQL 17 单库双 Schema 的 44 张表 V1 迁移，PostgreSQL 实例 CI 验证。
- 用户和 Agent 可信 OIDC 身份映射；有效状态与组织隔离。
- REST 个人信息、Agent 与数据源只读列表，以及 Agent/数据源新增、编辑、撤销/停用。全部按可信用户组织和显式角色权限校验；变更成功后追加 dg_system_event 操作记录。
- MCP 工具 `list_datasources` 具有真实平台库查询与 Agent 授权过滤；不返回连接密钥或主机。
- MCP 其余五工具仍为受控拒绝。
- SQL 词法预检查只用于拒绝，不会授予 SELECT 执行权限。
- Vue3 管理台 OIDC 登录、真实列表、Agent/数据源新增、编辑、撤销/停用表单，Node CI 构建。
- GitHub Actions 后端、前端、真实 PG SQL 初始化、MCP JSON 契约检查。

## 最新进展（2026-10-22）

已提交 OIDC 用户登记及受限角色委派、Agent 机器身份与客户端校验、Agent 数据源发现授权、只读 ENV 凭据引用和严格 TLS 连接测试接口；Vue3 新增相关页面入口。相关 API 经过 Maven/TypeScript 构建，仍需要登录态及真实目标数据库端到端测试。SQL 查询和写入执行仍禁用。

## 未完成或尚未通过验证

- Agent OIDC 机器身份登记、权限授予与启用，数据源连接认证与启用、统一 RBAC 用户管理。已提供离线首次管理员初始化 CLI。
- MySQL/PG 目标数据库连接器与真实安全 SELECT、元数据枚举。
- ShardingSphere AST 安全认证、策略匹配与敏感字段脱敏。
- Flowable 审批单与任务业务接口、审批人授权回避。
- 安全写执行器、非幂等重试保护与 UNKNOWN 对账。
- 真实 SQL 审计、告警、性能压测、备份恢复与生产安全测试。

**禁止将 SQL 词法结构检查作为允许执行的依据。业务 SQL 执行未开放。**
