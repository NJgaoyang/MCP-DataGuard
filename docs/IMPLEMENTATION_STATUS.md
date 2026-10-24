# MCP DataGuard implementation status

更新：2026-10-24。基础分支 `dev20261022`，生产加固候选见 PR #2。
**代码完备性、隔离验证通过、预生产真人验收、正式生产批准是四种不同的状态**；目前生产为 **NO-GO**。

## 已实现且具备隔离验证证据

- Java 21 / Spring Boot 网关，Spring AI Streamable HTTP MCP；PostgreSQL 17 `platform` 与 Flowable schema；Flyway V1/V2/V3。
- Vue 3 / Ant Design Vue 后台，内置原生 BCrypt 登录、服务端摘要 Session、首次强制改密、账号锁定、跨组织身份隔离和 RBAC；**默认运行不依赖 Keycloak**。
- Agent 独立令牌、MCP SQL 预检、工具/字段 ACL、受限 PostgreSQL 物理 SELECT、TLS verify-full、默认字段脱敏、行数和时间预算、SQL 读取审计回执、令牌和授权实时撤销。
- 两级 Flowable 业务审批、本人待办、防自审、Outbox 幂等消费、到期/拒绝/取消；独立 JVM CertifiedWriteWorker、UNKNOWN 告警和双人对账后禁止自动重放。
- 经一次性 PostgreSQL 17 验证的 TLS 最小权限只读账号认证、Java Worker COMMIT 后 SIGKILL、真实 TCP 断连、MCP HTTP READ ALLOW/DENY；隔离环境 CI 执行关键套件时不得跳过。
- HMAC HTTPS 告警发送端，TLS/重定向/DNS 固定连接安全测试；开发测试机的 GPG 加密本地备份/离线恢复和定时器。
- 生产化候选 PR #2 包括正式专用 Compose（按 digest 指定镜像、外部可信 CA PostgreSQL、非 root 文件密钥）、分阶段签署门禁、运行态镜像验证、漏洞扫描与 SBOM、原生登录限流、外部审计锚定发送端、批准目标专属 MCP 只读负载测试工具。

## 仍须现场验收与实际配置（阻断生产上线）

1. PR #2 独立审查、合并，GitHub 受保护分支与 required CI checks 生效。
2. 真实业务人员用浏览器/HTTP 与正在运行的 Flowable、Outbox 完整审批联调。
3. 逐个正式 PostgreSQL 目标的数据源证书、物理账号、表列权限和脱敏认证；生产只读需独立批准。
4. 部署级 Java Worker 断连/重启/未知结果及两名不同值班人的人工对账。
5. 真实外部告警收件人、不可篡改审计接收端、正式密钥托管轮换、可信生产 DNS/TLS 和网络隔离。
6. PostgreSQL 容器安全启动限制在 DBA 维护窗口应用；真实备份 RPO/RTO、本地离线恢复与单机全损风险签收。
7. 实际镜像 CVE 修复、SBOM/镜像签名、真实认证负载长稳/渗透测试和发布数据库回滚可行性。
8. DBA、安全与业务三名独立批准人员对实物证据和不可变 digest 的正式签署。

当前测试服务器没有正式发布证据包和外部告警接收端；真实 SQL 读写及 Flowable Outbox 不得因通过合成测试而开启。

参见 `docs/PRODUCTION_ACCEPTANCE_EXECUTION.md`、`docs/PRODUCTION_DEPLOYMENT.md`、`docs/PRODUCTION_RELEASE_GATE.md` 与 Issue #3。
