# DataGuard MySQL 8.4 隔离测试环境

> 2026-10-24：仅用于 DataGuard 的 MySQL 连接测试、SQL 语法审查及最小权限验证；平台数据库继续使用 PostgreSQL。

## 安装与隔离边界

- 主机：已授权开发机 `VM-0-4-tencentos`，容器 `dataguard-mysql-test`
- 镜像：`mysql:8.4`；测试时观察到服务端版本 `8.4.13`
- 主机映射：`127.0.0.1:3307 -> 3306`（仅本机回环地址；未发布公网）
- 独立命名卷：`dg_mysql_test_data` 挂载至 `/var/lib/mysql`；不可作为构建缓存清理目标
- 测试库：`dg_mysql_test`，合成表 `readonly_probe`（两行非生产数据）
- 最小权限账号：`dg_probe_ro`，仅对 `dg_mysql_test.readonly_probe` 授予 `SELECT`；`UPDATE` 必须失败
- TLS：`require_secure_transport=ON`，测试 CA、服务器证书 SAN 包含 `localhost` / `127.0.0.1`，JDBC 使用 `sslMode=VERIFY_IDENTITY`
- 数据库密钥与 TLS 测试私钥保存在开发机 `/opt/mcp-dataguard-work/secure-test-secrets/mysql84/`，仅本机授权管理员可读，**不在 GitHub 中保存**。证书为短期测试 CA，需在到期前重新签发。
- 容器启动策略：`unless-stopped`。与 `deploy-postgres-1`、`deploy-gateway-1` 的数据卷互不共用。

## 可复现的 MySQL 接口验收

`dataguard-server/src/test/java/io/github/mcpdataguard/api/MySqlDatasourceTlsIntegrationTest.java` 覆盖：

1. 真实 Connector/J 和 MySQL 8.4 身份校验 TLS 握手，`Ssl_cipher` 非空，SELECT 查询成功；
2. MySQL 物理只读用户执行 `UPDATE` 被数据库拒绝；
3. DataGuard `DatasourceConnectionController.test` 按真实 MySQL 数据源参数执行 `VERIFY_IDENTITY` 连接检查，返回 `sqlExecutionEnabled=false`；非白名单地址拒绝；
4. MySQL SQL 解析拒绝 UPDATE、多语句注入。

此文件内共有 **3 个 JUnit 用例**，第 2、4 类安全断言被分别包含在相应测试中。

仅在已授权测试开发机、root-only 密钥存在时运行：

```bash
export DG_MYSQL_TEST_PASSWORD_FILE=/opt/mcp-dataguard-work/secure-test-secrets/mysql84/read-password
export DG_MYSQL_TRUSTSTORE_FILE=/opt/mcp-dataguard-work/secure-test-secrets/mysql84/client-truststore.p12
export DG_MYSQL_TRUSTSTORE_PASSWORD='isolatedtestonly'
bash bin/verify-mysql-connection-test.sh
unset DG_MYSQL_TEST_PASSWORD_FILE DG_MYSQL_TRUSTSTORE_FILE DG_MYSQL_TRUSTSTORE_PASSWORD
```

命令从测试专用密钥文件读取账号凭据，且自动核对三个 JUnit 用例全部执行、没有跳过。**不要**将测试密码转写到 GitHub、工单或聊天。

## 与核心生产安全流程的关系

MySQL 的`数据源连接探测`和`只读 SQL 语法分析`可用，不代表 MySQL 已获准接入真实 MCP SQL 查询或受控写入。当前 DataGuard 的 `ReadOnlyQueryService`、`IsolatedReadOnlyExecutor`、`CertifiedWriteJdbcExecutor` 仍仅认证 PostgreSQL。MySQL 不应启用这些执行开关。

另一方面，核心 `Agent Token -> MCP HTTP SELECT -> 脱敏 -> 审计`、`Flowable 多级审批`及`受控写入 UNKNOWN` 继续由一次性 PostgreSQL 17 容器跑 `bin/verify-core-security-gates.sh` 验收（共 20 个 PostgreSQL/Flowable 集成用例），并且真实线上读写开关保持关闭。

## 运维检查

```bash
docker ps --filter name=dataguard-mysql-test
docker inspect dataguard-mysql-test --format '{{.State.Status}}'
ss -lnt '( sport = :3307 )'
docker volume inspect dg_mysql_test_data
docker system df
```

**不要**为释放空间运行 `docker volume prune`、`docker system prune --volumes` 或删除 `dg_mysql_test_data`。允许单独运行 `docker builder prune -a -f` 清理*未使用的构建缓存*，不删除容器或数据卷。
