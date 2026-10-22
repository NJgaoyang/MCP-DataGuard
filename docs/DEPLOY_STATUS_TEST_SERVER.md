# 测试服务器部署记录（2026-10-22）

目标机器 TencentOS Server 4，公网 IPv4 110.42.132.44。

- Docker Compose 创建 PostgreSQL 17 数据库 `dataguard`，`platform` 和 `flowable` 两个 Schema。
- Flyway V1 已在目标数据库创建 44 张 dg_* 平台业务表（另有 Flyway 历史表）。
- Flowable 8 已初始化自己的引擎表，成功部署示例两级 BPMN。
- Spring Boot 4 MCP 网关实际启动并注册 6 个工具。写入和通用读取 SQL 的业务功能仍然禁用。
- Vue3 与 Nginx 已构建并启动。
- Keycloak 26.4 测试 Realm `dataguard` 与 public PKCE Client 已创建。
- Caddy 2.11 在公网 IP 成功领取 Let's Encrypt shortlived 证书，HTTPS 证书链验证通过。
- 验证：公网 GET / 为 HTTP 200，未登录 /api/v1/me 为 401，
  Keycloak realm OIDC metadata 为 200。
- 首个 OIDC 账号 dgadmin 和平台同名管理员及组织已经创建；**该测试账号的登录密码还需由管理员单独设置**。
- 旧机器的 MySQL/ProxySQL/Audit Web 三个容器已经停止和删除；原磁盘数据保留。

注意：此记录仅代表开发机部署成果，并不代表 Flowable 审批到数据库写入的安全链路完成。
IP 证书有效期较短，由持续运行的 Caddy 负责续订。明文配置及数据库凭据不进入 GitHub。
