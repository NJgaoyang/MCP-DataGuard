# 已实现的只读 Agent 数据源发现：开发版

MCP 工具 `list_datasources` 现在使用数据库内已注册的 Agent 身份返回数据源列表。

调用链：OIDC JWT（先验证签发者与资源受众）→ 可信 JWT iss/sub → 四字节大端长度前缀 SHA-256 → `dg_agent_identity` 原始 issuer/subject 二次比对 → 有效 Agent 和租户 → `dg_agent_permission` 显式 ALLOW / DENY 优先 → 仅返回 ACTIVE 数据源的非敏感字段。

安全行为：未知 Agent、吊销身份、无权限数据源、其他租户一律不可列出；MCP 工具不返回数据库密码、主机、连接参数。环境只接受 DEV/TEST/STAGING/PROD，页大小限制 1～100。仅支持第一页，传入 cursor 拒绝而不静默忽略。

注意：权限配置的创建接口、Agent 注册后台、变更审计和租户管理尚未完成，建议仅在隔离开发环境演示。其他五个 MCP 工具依然全部默认拒绝，未获授权时绝不能连接业务数据库。

服务端 canonical 哈希定义：SHA-256( BE32(len(UTF8(issuer))) || UTF8(issuer) || BE32(len(UTF8(subject))) || UTF8(subject) )。注册 Agent 身份时应使用同一算法，不能直接把 issuer/subject 拼接。

完整业务 SQL 入口的授权、AST 语义认证、单独只读账号和结果脱敏完成前，不得对外开放数据库执行。
