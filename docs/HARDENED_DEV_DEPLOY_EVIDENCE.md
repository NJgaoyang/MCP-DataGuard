# DataGuard 生产安全加固：开发机部署验证记录

日期：2026-10-24。开发机 `VM-0-4-tencentos`，项目 `NJgaoyang/MCP-DataGuard`，分支 `dev20261022`。

## 1. 已在开发机实际生效的安全配置

- Gateway：镜像代码提交 `faf4aed`，只读根文件系统、`no-new-privileges`、全部能力删除、内存上限 1 GiB、PID 上限 256、HTTPS 健康检查 200。
- Workflow：只读根文件系统、`no-new-privileges`、全部能力删除、内存上限 1 GiB、PID 上限 256，Flowable 健康检查 200。
- Web：镜像代码提交 `ccd6339`，组织编码登录、只读根文件系统、`no-new-privileges`、内存上限 256 MiB、PID 上限 96，HTTPS 首页 200。
- Caddy：只读根文件系统、`no-new-privileges`、内存上限 256 MiB、PID 上限 128；API/MCP 返回 `Cache-Control: no-store`，并带有 `Permissions-Policy` 和 CSP Report-Only。仍需未来根据实际前端依赖升级为强制执行 CSP。
- PostgreSQL：已有数据卷保持不变，Flyway V4。**未重建 PostgreSQL 容器**，资源限制留待单独维护窗口实施；不可宣称 DB 运行时已加固。
- MySQL：测试数据库容器 `dataguard-mysql-test` 继续运行，仅绑定 `127.0.0.1:3307`。不支持以此证明 MySQL 真实 MCP 读写已获认证。
- 所有数据库 SQL 执行和 Flowable 消费开关保持安全关闭。匿名 `/mcp`、`/api/v1/me` 返回 HTTP 401。

可重复检查：
```bash
bash bin/verify-hardened-compose.sh
bash bin/verify-live-hardened-dev.sh
```

注意，常规 `bin/start-dev-deploy.sh` / `bin/start-public-test.sh` 现默认合并 `deploy/compose.hardened.yaml`，后续发布不得省略该文件。

## 2. 本轮验证证据

- 21 个 PostgreSQL / Flowable 集成用例：**0 失败，0 跳过**。
- 真实 Agent Token 的 MCP HTTP 协议测试与真实只读 ALLOW/脱敏/审计测试通过。
- 多组织同名用户登录隔离和原生登录审计测试通过。
- MySQL 8.4 的 TLS / 物理只读 / 数据源连接 / SQL 解析测试：3 个用例通过。
- 公钥加密 `pg_dump -Fc`、SHA-256 清单、仅在一次性 PostgreSQL 容器中恢复：`ENCRYPTED_BACKUP_RESTORE_DRILL_PASSED`。
- `production-hardening.yml` GitHub CI 已运行成功，后端 / 合约检查及浏览器构建运行成功。
- 加密恢复演练中使用的**一次性测试私钥**在验收后已清理。

## 3. 发布前仍然阻断的条件

- 真实生产公钥、密钥托管、生产加密备份定时器**未开启**；异地不可变备份、WAL/PITR、真实 RPO/RTO 尚未建立。
- 生产证书和网络边界认证、正式镜像 digest / 签名、依赖漏洞整改、渗透测试、负载测试及安全基线审计尚未通过。
- 真实业务用户及审批人的 Flowable 全流程联调，物理断网 / COMMIT 崩溃故障注入和 UNKNOWN 人工对账尚未在认证预生产环境完成。
- PostgreSQL 容器资源控制等待维护窗口，长期运行监控、告警通知、外部审计不可篡改存证、值班响应与 Go/No-Go 签字仍需落实。

**上线判定：NO-GO（生产环境）**。本记录只证明开发环境和隔离验收已达到本轮安全加固要求，不是生产审批授权。完整标准见 `docs/PRODUCTION_RELEASE_GATE.md`。
