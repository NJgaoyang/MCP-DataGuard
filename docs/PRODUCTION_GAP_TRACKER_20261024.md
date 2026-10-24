# MCP DataGuard P0/P1 实施与生产验收现状（2026-10-24）

分支：`dev20261022`。部署环境：`VM-0-4-tencentos`，测试地址 `https://110.42.132.44/`。

> 本文是证据进度记录而非正式生产签字。**异地备份明确排除**；本地备份及隔离恢复保留。

## 已实现并验证

| 领域 | 实施与结果 | 尚不代表 |
|---|---|---|
| Flowable 业务审核 | `ApprovalDecisionController` 按真实数据库角色 `db-owner` → `dba` 区分阶段，移除业务审批人的 `identity:admin` 依赖；拒绝受同阶段角色、Agent 所属用户回避约束；`/api/v1/approvals/my-inbox` 只返回本人当前审核节点；Web 增加“我的待审批” | 正式生产用户 HTTP、真实启用的 Outbox 消费联调 |
| UNKNOWN 人工对账 | `ExecutionReconciliationController` 禁止原审批人及 Agent 所属用户参与对账，拒绝重复或跨租户签署，双独立操作员一致也不允许自动重放 | 真正 Java Worker 进程在数据库提交时被 SIGKILL 的全部网络时序 |
| Worker 异常安全告警 | `UnknownWriteIncidentService` 对执行失败和租约超时使用统一租户隔离关键事件，去重、不存 SQL 明文；真实 PostgreSQL 集成测试通过 | 已接通值班人员的生产通知渠道 |
| PostgreSQL 写入故障 | 一次性 PostgreSQL 中 COMMIT 前断连回滚、COMMIT 后服务端断连数据持久化；一次性 SQL 客户端 COMMIT 后 SIGKILL，UNKNOWN 不重领 | 正式业务执行器的生产环境故障混沌验收 |
| MCP 只读 | 隔离授权 Agent Token 的真实 MCP HTTP、SELECT、TLS、脱敏、吊销、审计通过，读写执行开关仍关闭 | 真实目标业务数据源的认证和生产开放 |
| 本地备份 | 开发机已启用 PostgreSQL GPG 加密备份、备份健康巡检、每周安全保留期三个 Systemd Timer；实测 2 份以上加密备份、SHA256 与 0700/0600 权限、隔离 PostgreSQL 恢复 4 张关键平台表成功 | 正式密钥保管、RPO/RTO 和跨故障域备份（已明确放弃异地备份） |
| 告警通知基础设施 | `notify-operational-incidents.py` 支持 HTTPS、精确主机白名单、HMAC 签名、聚合事件去重、恢复通知和失败重试；离线策略测试通过 | 已配置生产 Webhook URL/密钥/接收人及通知真实可达 |
| 发布安全 | 容器只读根文件系统、资源限额及 HTTPS 响应头生效；发布三方 GPG 签名工具，Go/No-Go 缺项报告；GitHub CI 加固检测 | DBA/安全/业务负责人已完成签字 |
| 轻量性能检查 | 开发机内部健康和匿名 MCP 共 80 请求，4 workers、上限 10 RPS，P50=4.8ms，P95=6.4ms，错误 0 | 真正 SQL 查询、审批、写入、长时间运行和峰值容量测试 |

最新完整**隔离安全回归通过 23 项 PostgreSQL/Flowable 集成测试、真实 MCP HTTP、真实 TCP COMMIT 断连、一次性 SQL 客户端 SIGKILL**。CI `production-hardening.yml` 对应故障及本地备份/告警测试也通过。

开发机 Gateway 已部署 `e6398c4`，Web 已部署 `5d10e8a`，均保留了旧镜像回滚标签。部署后健康 200、匿名 MCP 401。**`DG_ENABLE_READ_EXECUTION=false`，真实写入与 Flowable Outbox/dynamic deployment 仍关闭。**

## 尚未达成正式生产上线标准的阻断项

1. **真人用户工作流演练**：在受控预生产环境真实登录申请人、db-owner 和 DBA，完成申请→审批→拒绝→取消→超时→历史同步；先验证 Outbox 幂等再有条件启用工作流消费。测试中的 mock JWT 不等同于真人登录。
2. **真实 Java Worker 故障演练**：包括进程 SIGKILL、网络分区、DB COMMIT 前/后、重启、UNKNOWN 人工对账和恢复审批；目前隔离客户端模拟不等于 Java Worker 运行时验收。
3. **生产数据库安全认证**：实际数据源逐个评估表/列权限、TLS、最小权限 PostgreSQL 账号、脱敏、查询限额、审批写入角色后才能有条件开启读写。MySQL 真实 MCP SQL 执行器尚不在 PostgreSQL 首版认证范围。
4. **本地备份正式运营**：开发用 GPG 密钥需要替换为正式受控密钥，明确本机备份留存、磁盘告警、恢复负责人、RPO/RTO 和定期恢复责任；无异地备份时需显式接受整机损毁造成全量丢失的风险。
5. **告警接收人联通**：设置 `/etc/dataguard/operations-alert.env`、真实 HTTPS Webhook 和 HMAC 密钥，验收到达、重试和恢复消息。未提供接收端时不能宣称自动通知已经上线。
6. **生产安全基线**：正式域名及可信 CA、KMS/Vault、测试凭据轮换、可信网络与最小暴露、镜像签名/SBOM/漏洞整改，以及 PostgreSQL 容器尚需重建才能生效的部分权限限制。
7. **专业负载/安全测试**：并发业务 SQL、审批 Outbox 峰值/延迟、长时稳定、资源容量、渗透测试和故障回滚；轻量健康检查不能替代它们。
8. **正式 Go/No-Go**：收集经 DBA、安全及业务负责人的真实签名和可审核的生产验收记录；空白证据应由 `bin/check-production-readiness.sh` 输出 NO-GO。

## 当前结论

**代码与开发机安全基础显著完善，但正式生产仍为 NO-GO。** 真正需要外部环境/人员配合的是业务测试账号/组织角色、生产数据库与域名、告警 Webhook、密钥管控、性能/渗透审核及负责人签署。无证据不得解除只读/写入及 Flowable 开关。
