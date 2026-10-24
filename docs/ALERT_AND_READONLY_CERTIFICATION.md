# DataGuard 生产安全：告警接入与 PostgreSQL 只读账号认证

本指南只涉及**已获审批的环境**。本机加密备份已具备自动化能力，**异地备份不在本项目范围内**。以下操作不自动开启真实 SQL 读写或 Flowable Outbox。

## 1. 告警通道安全要求

应用告警默认关闭，无法确认目标地址时必须拒绝发送。运营安全人员准备以下资源：

- 已经使用受信任 CA 颁发证书、支持 HTTPS 443 的公网告警接收端；对方应校验每条消息的 `X-DataGuard-Signature-SHA256`（HMAC-SHA256）与 `X-DataGuard-Event-Id` 去重标识；
- 单独生成的至少 32 字节随机 HMAC 密钥，以模式 `0600` 写入**主机**受控文件；不要写进仓库、Compose、工单、日志或 CI；
- 告警状态目录模式 `0700`，专用于保持已成功投递的事件指纹；
- `/etc/dataguard/operations-alert.env` 受限访问，定义以下变量（示例为占位符，不可直接启用）：

```dotenv
DG_ALERT_WEBHOOK_URL=https://your-approved-public-alert-domain.example/incident
DG_ALERT_WEBHOOK_HOST=your-approved-public-alert-domain.example
DG_ALERT_WEBHOOK_HMAC_FILE=/etc/dataguard/alerts/webhook-hmac.key
DG_ALERT_STATE_DIRECTORY=/var/lib/dataguard/operations-alert-state
DG_ALERT_DB_CONTAINER=deploy-postgres-1
```

不使用外部代理。告警端 DNS 解析的**每一个 IP**均必须是公网可路由地址；网络连接固定于校验通过的 IP、证书使用原始主机名验证，HTTP 30x 跳转被拒绝。非 2xx 不推进告警指纹状态，因此下一次轮询会重试。内容只有 `unknownWrites`、`criticalAlerts`、`deadLetters` 三项计数和事件时间，没有 SQL 明文。

验收步骤（必须使用测试事件、批准的地址）：

```bash
python3 bin/verify-operational-alert-policy.py
python3 bin/verify-operational-alert-https-e2e.py
# 只有正确部署受信 CA/密钥/告警接收端后执行下列命令
sudo systemctl start dataguard-operations-alert.service
sudo systemctl status dataguard-operations-alert.service
# 签名与接收、失败重试、恢复消息人工双侧确认后才启用
sudo systemctl enable --now dataguard-operations-alert.timer
```

如果没有真实接收端，不启用 Timer、不创建虚假的成功回执。现场验收要记录发送端 Journal 事件 ID 与接收端 ACK、值班人员确认时间、重试和恢复验证。

## 2. PostgreSQL 最小权限只读账号认证

`bin/certify-postgres-readonly.sh` 是认证用检查器，不是执行 SQL 服务。安全管理员和目标数据库 DBA 必须先确认源地址、账号、数据库及最小 SELECT 范围，仅在许可的测试或生产维护窗口运行。不要将管理账号、平台 PostgreSQL 管理用户或任何已具备写权限的账号用于 MCP 只读角色。

客户端要求安装 PostgreSQL `psql`，准备用户 `0600` 权限 `PGPASSFILE`、目标 CA 证书 `PGSSLROOTCERT` 和可验证主机名的 TLS 证书。使用企业批准的证书和密码文件安全保管流程。

```bash
export DG_CERT_APPROVED_TARGET=yes
export DG_CERT_HOST=approved-db.example.com
export DG_CERT_PORT=5432
export DG_CERT_USER=dg_read_only
export DG_CERT_DATABASE=approved_database
export DG_CERT_SCHEMA=approved_schema
export DG_CERT_TABLE=approved_select_probe
export PGSSLMODE=verify-full
export PGSSLROOTCERT=/path/to/approved-ca.crt
export PGPASSFILE=/path/to/restricted.pgpass
bash bin/certify-postgres-readonly.sh
```

该脚本使用只读事务与 8 秒语句超时，验证 TLS、账号没有特权与任何 DML/DDL、允许的 SELECT 探测、会话 RLS 设置；它不会打印业务数据。若发现 UPDATE、INSERT、DELETE、TRIGGER、CREATE、序列写权限、超级用户或绕过 RLS，直接判定未通过。还应由数据所有人单独验收表和列 ACL、用户角色绑定、字段掩码、限流、吊销与审计，这是脚本之外的责任。

```bash
bash bin/verify-postgres-readonly-certification.sh
# 独立 PostgreSQL 17、无外网、测试证书和账号；不访问正式数据库
```

## 3. 仍然阻断生产开放的证据

- **真实人员审批**：目前原生 Token + SessionFilter + 两级角色 + Flowable + Outbox 已在隔离数据库链路通过，生产服务 Outbox 开关仍关闭；需要真实组织的前后端联调、反向代理和正式审核责任。
- **写入确认**：隔离 Java Worker 进程已覆盖真实提交后 SIGKILL、UNKNOWN 高危告警、重启不重放。仍需正式预生产网络分区和受控故障演练、DBA 双人对账。
- **外部告警**：需业务方提供真正受控的 Webhook 域名/证书、接收者和私有 HMAC 密钥，现场证明 OPEN/RECOVERED/重试及追踪。
- **正式数据库**：需目标业务 PostgreSQL 只读账号与 CA，并逐个执行上述认证，再由业务数据所有人签署脱敏/访问范围。
- **生产密钥和审计**：正式 KMS/Vault、密钥轮换、镜像签名、可信域名、渗透及性能测试仍需现场资源、人工审批和实际证据。

**未满足上述条件，保持 `DG_ENABLE_READ_EXECUTION=false`、写入默认关闭、Flowable Outbox 关闭，发布判定 NO-GO。**
