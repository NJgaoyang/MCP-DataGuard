# V2 StarRocks 数据源部署及安全验收

平台元数据、Flyway、Flowable 均继续使用 **MySQL**，绝不将 StarRocks 配为平台存储。

## 连接方式

- StarRocks FE Query Port 通常为 `9030`，使用 MySQL 兼容协议和现有 MySQL Connector/J。
- 数据源 `dbType=STARROCKS`，`host` 填 TLS 证书覆盖的 FE DNS 名，`databaseName` 填 StarRocks 内部数据库名。
- 目前仅对 `default_catalog` 的内部表开放；不支持 external catalog、跨库查询或用户提供的自由 SQL。
- 后端按 `jdbc:mysql://FE:9030/DB?sslMode=VERIFY_IDENTITY&...` 连接；不允许明文降级。需要目标 StarRocks FE 正确配置 TLS 和主机名证书。
- 必须将 FE 主机精确列入 `DATAGUARD_READ_HOSTS`，并限制仅此应用服务器访问 FE Query Port。

StarRocks 官方文档：<https://docs.starrocks.io/docs/sql-reference/sql-statements/account-management/SHOW_GRANTS/>、<https://docs.starrocks.io/docs/administration/user_privs/authorization/user_privs/>。

## DBA 权限准备

示例 SQL（实际用户名/host 身份由 DBA 按部署替换）：

```sql
-- 示例：不要在平台使用 root、db_admin、user_admin 等权限账号
CREATE USER 'dg_ro'@'%' IDENTIFIED BY '<strong-password>';
-- 仅单表、直接赋予 SELECT，不使用 ALL TABLES、db.* 或任何写入授权。
GRANT SELECT ON TABLE ads.ads_order_kpi_day TO USER 'dg_ro'@'%';
-- SHOW GRANTS FOR CURRENT_USER() 应只出现上述直接 SELECT、
-- 可能需要的 default_catalog USAGE 和系统内置 public 角色。
```

程序对于不认识的 StarRocks 权限格式、额外角色、宽泛 SELECT、管理/写入权限、授予选项均拒绝。运行时仅使用低权限账号可执行的 `SHOW GRANTS FOR CURRENT_USER()` 和 `SELECT CURRENT_ROLE()`，要求当前激活角色只有 `public`；不会给运行账户赋予 `user_admin`。

**DBA 必须独立验收 `public`**：StarRocks 的 `public` 角色可被修改，普通只读账号不能执行 `SHOW GRANTS FOR ROLE public`。此管理员检查应由 DBA 使用受控账号执行并留存证据；只有确认 `public` 没有写入或管理权限后才能在生产启用该数据源。不能误称应用运行时已证明 `public` 安全。

## 验收脚本化测试需求

1. 同一账户直连 FE 并通过 `SHOW GRANTS FOR CURRENT_USER()` 与 `SHOW GRANTS FOR ROLE public` 核对实际输出。
2. 验证 TLS 校验、读账号物理不能执行 INSERT/DELETE/UPDATE/CREATE/GRANT。
3. 配置平台表白名单、owner 审批、owner 列白名单、Agent 表和列授权，确认 MCP 只返回授权列。
4. 删除 Agent 表/列授权、令牌撤销、owner 审批过期，验证立即拒绝。
5. 不允许用户 SQL 原文任意执行；危险表名注入、不同库、结果上限和超时必须拒绝。
6. 断开 FE、停用数据源、目标库取消授权，验证失败记录不泄露凭证、SQL 结果。
7. 并发与极端数据类型、高耗时查询（StarRocks FE 与客户端的超时行为）逐项认证。

> StarRocks 的 `public` 在所有用户会话默认激活，且可能被 DBA 授予额外权限。平台必须检查该角色；测试环境的默认角色配置不能代替生产检查。当前无真实 StarRocks FE 验收证据，安全放行 NO-GO。
