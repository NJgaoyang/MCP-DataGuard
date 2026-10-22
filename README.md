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
