# 用户和 Agent 内置认证

项目已经改为 **PostgreSQL + BCrypt + 随机 opaque 会话 / Agent Token**，
不再使用 Keycloak/OIDC 作为运行时依赖。

详情参阅 [内置认证指南](NATIVE_AUTH.md)。

`GET/POST /api/v1/admin/users`、`/api/v1/admin/roles` 继续使用组织
RBAC。新用户创建时提供复杂初始密码，首次登录强制修改；
Agent 的 `POST /api/v1/agents/{agentId}/native-token` 用于
独立随机访问令牌签发，支持轮换与撤销。

数据源数据库用户名和密码只通过受控服务器环境变量引用配置，
不能通过浏览器返回。SQL 真正读写执行依然默认拒绝。
