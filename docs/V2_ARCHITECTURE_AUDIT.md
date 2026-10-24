# MCP DataGuard V2 — 代码审计与架构整理（2026-10-24）

基线：`dev202610241`，HEAD `edf5b5b142078b4b13ec1e07e818590f8aaf0010`。
开发：`refactor/dataguard-v2`。目标：MySQL 平台库、MySQL + StarRocks 业务数据源、Spring Boot 模块化单体、内嵌 Flowable、Vue 3、JAR + systemd，非 Docker 发布。

## 代码审计事实（源码核实，不等于生产验收）

| 范围 | 观察 | V2 决策 |
|---|---|---|
| `pom.xml` | 默认 reactor 仅 `dataguard-standalone` | 保留单体构建，不合并旧服务 |
| `dataguard-server/` | 旧 Spring AI MCP/PostgreSQL/OAuth2 网关，默认不编译 | 历史代码，仅参考；后续安全移出主仓库前需迁移引用 |
| `dataguard-workflow/` | 旧版分进程 Flowable/Outbox | 历史代码；保留 `standalone` 内嵌 Flowable |
| `dataguard-sql-core/` | 历史 ShardingSphere/只读 SQL 解析和 Workflow contract | 未来若开放任意 SQL 时再重新评估，不直接引入运行路径 |
| `dataguard-standalone/` | MySQL Flyway V1-V9、内置用户/强制改密、Agent Token、MCP HTTP、表列白名单、审批、审计、受控 MySQL 读取 | 主后端重构基线 |
| `dataguard-web/` | Vue 3 + Ant Design Vue；与单体 API 绑定 | 保留并增量优化，不先全量换 UI 框架 |
| `deploy/compose*.yaml`, PG 备份与 Worker 工具 | 旧生产镜像/PG 流程 | 不适用于 V2 非 Docker 上线；后续移入历史目录 |
| `bin/build-standalone.sh`, `bin/install-standalone-systemd.sh` | JAR + systemd 构建安装 | 保留，需新环境真实验收 |
| `.github/workflows/standalone-mysql.yml` | MySQL CI、Vue 构建、JAR、登录/审批脚本 | 扩展 V2 分支安全门禁 |

## 真实闭环（V2 预期）

MCP HTTPS POST → Agent Token 验证 → owner 有效账号 → 表白名单 → 真实用户 Flowable 批准（有效期内）→ Agent Token 表/列授权 → 目标主机白名单 → MySQL/StarRocks 物理只读权限检查 → 有限单表列投影 SELECT（最多 100 行，超时 5 秒）→ 审计 → 返回。

所有平台身份、凭证摘要、数据源配置、授权、审批记录、审计日志以及 Flowable 存于平台 MySQL。
MySQL、StarRocks 仅作为业务只读数据源，不创建平台元数据表。

## 安全审计结果

1. 原 `ReadApi` 使用 `SELECT *` 再对未授权字段脱敏：V2 已改为仅投影已授权列，避免物理读取不授权字段。
2. 原 V1 所有数据源连接固定 `jdbc:mysql`：V2 增加显式 `db_type`（默认 MYSQL，允许 STARROCKS），StarRocks FE 走 MySQL 兼容 JDBC 协议。
3. 原物理权限检查只理解 MySQL `SHOW GRANTS`：V2 加入单独 StarRocks 授权审查，不能以 SELECT 语句本身代替物理只读保障。
4. 当前 MCP 主路径仅提供有限单表 `execute_readonly`，不是开放 `execute_query(sql)`：支持安全读闭环，但复杂 SQL AST、JOIN、WHERE、聚合等自由查询仍未迁移到 V2。
5. Flowable 审批基于表访问许可，不是任意 SQL 本体签名：在允许有限表读取时成立；未来可编辑 SQL 的审批必须绑定不可更改的 SQL 哈希、参数、版本、执行次数与到期时间。
6. 审计以 MySQL 表为载体，尚未具备独立的抗篡改外部存证及断连/宕机全链路运行证据。
7. 无真实 StarRocks FE、生产证书、DBA 专用账号及并发压力验证报告；当前不可声明生产 Ready。
8. 现有 `docs/IMPLEMENTATION_STATUS.md`、大量 `deploy` 文档描述历史 PG 版本，不能用于 V2 放行。

## 需重构 / 删除的历史文件（不立刻批量删除）

- **保留并演进**：`dataguard-standalone`、`dataguard-web`、`bin/*standalone*`、V1-V9 Flyway 历史迁移。
- **V2 不参与构建**：`dataguard-server`、`dataguard-workflow`、`dataguard-sql-core`、`database/postgresql`、`deploy/compose*.yaml`、PG 相关脚本与旧 README 内容。
- **后续删除前置条件**：完成新集成测试、找全脚本/CI/文档引用、验证 no-Postgres 运行依赖，再做隔离提交；不要删旧 Flyway 迁移文件（已投产的 MySQL schema checksum 必须保持兼容）。

## 模块化单体目标边界

在当前 Spring Boot 模块中先按 package/service 责任拆分：auth、agent、mcp、datasource、permissions、sql-security、executor、workflow、audit、monitor、system。
完成接口与数据库迁移后，再决定拆成多个 Maven 子模块；避免过早将单体改成多个部署进程。

> 本文基于指定分支源码静态审计。未声称已运行现场端到端验收。
