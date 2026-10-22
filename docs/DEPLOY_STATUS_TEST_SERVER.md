# MCP DataGuard 测试服务器交付记录（2026-10-22）

**分支：** `dev20261022`。**运行环境：** TencentOS Server 4，HTTPS `https://110.42.132.44/`。

## 当前架构

- Vue3 + Ant Design Vue 管理后台；Caddy 公网 HTTPS 反向代理。
- Spring Boot 4 / Java 21 API 及 Spring AI MCP Server；PostgreSQL 17。
- PostgreSQL `platform` 和 `flowable` 两个 Schema；Flyway V1、V2、V3 已执行。
- 采用 **内置账号密码 + BCrypt 哈希 + PostgreSQL 不可逆 token 摘要**。
- `admin` 属于 `dataguard` 组织，保留原 `org-admin` 角色。
- Keycloak/OIDC 已从默认前端、后端及公网路由配置移除；旧 Keycloak 容器/数据应在备份确认后单独清理，不影响新登录。
- Spring Boot 注册 6 个 MCP 工具，真实数据库 SQL 读写执行仍保持禁用。

## 现场验证

- 新版网关成功启动，Flyway V3 `native_auth` 成功，MCP 工具注册成功。
- 管理后台 GET / HTTP 200，未认证 GET /api/v1/me HTTP 401 为预期行为。
- `admin` 已检测到本地密码哈希，且 `must_change_password=true`。
- **首次密码修改端到端成功与否仍未验收**：用户曾报告 POST /api/v1/auth/password 返回 HTTP 401。
- GitHub `dev20261022` 的改密提示/错误区分修复可能领先于服务器正在运行的镜像，需要核对镜像版本后重建部署并实际测试。

## 安全说明

- 曾有测试密码被浏览器截图泄露；必须全部更换，不得写入 Git、交付文档、日志。
- 不通过 API 或 GitHub 重置管理员已设置的密码；请通过批准的安全恢复流程执行。
- 所有临时密码仅使用服务器权限受限的文件或交互式输入。
- 改密成功会撤销所有原有会话。初始强制改密由服务器认证层阻止业务访问。
- 生产环境仍需评估限流、防爆破、会话 Cookie/CSRF、审计留存、安全备份。
- SQL 执行安全链路尚未完成，不能启用生产数据库 SQL 执行。

## 回退和版本一致性

部署前保留平台数据库备份与上一版镜像；不得将 GitHub 之外的本地 `.env.*`、备份、口令文件推送至仓库。

测试机 `/opt/mcp-dataguard` 的 Git HEAD 曾滞后于同步源码，应以**目标 GitHub 提交 SHA + Docker 构建镜像摘要**共同确认运行版本；不要根据 `git status` 将已上传代码重复提交。

## 后续验收清单

1. 使用独立测试账号和一次性密码，确认首次登录只允许改密及退出。
2. 验证现有临时密码正确时，改密返回成功；错误原密码必须返回明确错误。
3. 改密后原令牌失效，新密码重新登录可访问 `/api/v1/me`。
4. 验证管理员 RBAC、用户管理、Agent Token 签发/轮换/撤销。
5. 验证登录及改密不把明文密码写入服务端日志或数据表。
6. 确认新服务已无 Keycloak 网络依赖，备份后清理旧身份容器。
7. 持续保持 SQL 执行关闭，直至 SQL 策略、只读连接、脱敏、安全审计全部验收。
