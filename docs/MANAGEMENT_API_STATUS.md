# Web 管理 API 纵切片

当前 Spring Boot 服务包含：

- `GET /api/v1/me`：以已验证 JWT 的 issuer + subject 映射 `dg_user`；必须用户启用且所在组织启用。
- `GET /api/v1/datasources`：必须具有组织内 `datasource:read` 角色权限；仅本组织数据源，不返回连接密钥、主机或连接选项。
- `GET /api/v1/agents`：必须具有组织内 `agent:read` 角色权限；仅本组织 Agent，不返回身份绑定密钥。
- 分页：`limit=1..100`，`offset=0..100000`。

所有管理端点均要求 OIDC JWT，映射使用服务端计算 SHA-256 及数据库里的原始 iss/sub 二次比对，不信任客户端传来的组织 ID。对于未登记、禁用或缺少权限的用户，返回 HTTP 403。

**这些仅为只读功能**。用户管理、Agent 注册、数据源新增编辑、SQL 规则、Flowable 审批、审计导出和 Vue UI 仍未实现。生产启用前还须增加管理员操作审计、速率限制、权限变更测试及 TLS 代理。
