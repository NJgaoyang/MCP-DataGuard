# MCP DataGuard V2 实施与验收台账

更新时间：2026-10-24。目标分支：`refactor/dataguard-v2`。
基线：MySQL 8.4 平台库 + MySQL / StarRocks 业务只读数据源；Java 21 / Spring Boot / Flowable / Vue 3；JAR + systemd。生产状态：**NO-GO**。

状态词：`代码已提交` != `CI 通过` != `真实数据库验证` != `生产准入`。

| 阶段 | 功能/工作项 | 当前证据和状态 | 遗留工作 |
|---|---|---|---|
| 1 | 代码结构与可复用模块审计 | 已完成初步静态审计；见 V2_ARCHITECTURE_AUDIT | 旧 PostgreSQL/容器功能依赖引用清理 |
| 1 | 新分支保护历史 | 已从 dev202610241 创建 refactor/dataguard-v2 | GitHub 分支规则与发布策略独立审查 |
| 2 | 平台 MySQL Flyway 数据源类型 | 代码已提交：V10，存量 MYSQL | 在真实存量库升级/回滚预演 |
| 2 | MySQL + StarRocks 数据源 API | 代码已提交：两种类型新增、编辑、列表、测试 | StarRocks 真实 FE/TLS/授权测试 |
| 2 | Agent MCP 认证/工具发现 | 复用 standalone 的 /mcp 和 Agent Tokens | SDK 协议完整性验证：多客户端与边界测试 |
| 2 | Agent、用户、表、列权限交集 | 已提交新增 Agent 列权限执行前交集与有限 SELECT 投影 | MySQL/StarRocks 多账号真实撤权/竞态测试 |
| 2 | SQL 安全查询 | 代码已提交 ReadProjection：仅有限单表 SELECT | 自由 SQL AST/WHERE/JOIN/聚合未实现，默认不开放 |
| 2 | 数据库物理只读检查 | MySQL 旧保护 + 新 StarRocks 直接表 SELECT / public-role 检查 | 真正 StarRocks 版本权限输出、角色与 TLS 验证 |
| 2 | Flowable 审批闭环 | 复用 standalone 申请/真实系统审批人/有效期；V2 未改动流程 | 浏览器与 MCP + MySQL + StarRocks 全链路联调 |
| 2 | 审计闭环 | 复用 standalone MySQL 审计/SQL_READ 记录 | 真实拒绝/超时/崩溃/审批检查、外部存证 |
| 3 | 前端数据源类型 | 代码已提交 MySQL/StarRocks 新增、列表、详情、筛选 | 整体按新原型做 UI 信息架构和交互回归 |
| 3 | SQL 安全查询 | 已实现 Vue 安全查询控制台：选择 MySQL/StarRocks、表、已授权列、限定行数；真实请求 `/api/read` | 高级 SQL 编辑器暂不开放；真数据源读取与审批联调尚缺 |
| 3 | 其它前端页面 | 已统一侧栏、工作台密度及蓝/灰视觉规范；沿用旧 Vue 3/Ant Design Vue | Agent、审批、审计、监控继续按原型完善 |
| 4 | 构建/安全 CI | V2 分支已开启 MySQL8.4 CI，验证 Maven 单元测试、Vue、单 JAR、登录/改密/Flowable/MCP/列授权撤权；`68150d2` 运行成功，见 [GitHub Actions](https://github.com/NJgaoyang/MCP-DataGuard/actions/runs/40106342577) | 后续新提交的 CI 必须重新成功；仍缺真实 StarRocks FE 测试 |
| 4 | StarRocks 真实集成测试 | 未完成 | 必须接入隔离 FE，证书验真、只读/越权、取消执行 |
| 4 | 并发/失败恢复 | 新增 16 个并发 MCP 受控元数据请求（8 个恶意标识符）、审计断言；已纳入 MySQL CI | JVM SIGKILL、目标库网络断开、真实 SQL 超时、资源上限与恢复演练 |
| 4 | 正式上线签署 | 未完成 | DBA/安全/业务负责人验收证据与正式批准 |

## 当前有限 SQL 协议能力

`/mcp` (Streamable HTTP JSON response) 支持：
- `initialize`、`ping`、`tools/list`、`tools/call`；
- `list_datasources`、`get_schema`、`describe_table`、`execute_readonly(sourceId, tableName, limit)`。
- 单次 1-100 行；不允许提交任意 SQL、不允许 UPDATE/DELETE/DDL。
- 未来目标 SQL Tool 必须先完成 AST、跨方言功能边界和审批 SQL 快照后才能发布。

## 开发者检查命令（不是已运行结果）

```bash
cd dataguard-web && npm ci && npm run build
cd .. && mvn -B -ntp -pl dataguard-standalone -am clean verify
bash -n bin/build-standalone.sh bin/install-standalone-systemd.sh
```

## 生产准入不得跳过

- MySQL 8.4 Flyway V1-V10 在全新空库与已有 V9 库均通过。
- MySQL 及 StarRocks 业务账户仅有已许可的 SELECT，并且系统再检查同一表、列授权。
- MCP 被撤销 Token、无审批、未授权字段、危险标识符、数据源停用均拒绝且有审计。
- Flowable 审批人是系统内用户，不能自审，审批完成后续执行与到期失效真实可重放演练。
- 浏览器真实登录 / 首次改密 / CSRF / 会话超时 / 管理接口 RBAC 检查。
- 完整并发压测、数据库断线、权限撤销竞态、进程退出、审计错误注入。
- TLS 主机名验证、正式密钥、告警收件人及运维日志、可回滚 JAR 发布程序。
- 发布版本与实际验收证据双人独立审查。

> 未经上述验收，V2 不得标记为生产 Ready。

## V2 变更追加说明

- 新增 `ReadSourceHostPolicy`：数据源测试与实际 JDBC 读取均检查相同的精确出站主机白名单，拒绝意外内网访问。
- `GET /api/sources` 仅允许 ADMIN：防止审批人或普通用户枚举内部主机信息；已有 HTTP RBAC 测试。
- StarRocks 普通读取账号使用 `SHOW GRANTS FOR CURRENT_USER()` 与 `SELECT CURRENT_ROLE()`，禁止额外激活角色。系统内置 `public` 的完整授权只能由 DBA 另行核实，普通只读账户不能查询此管理员权限视图。
- 当前未经已授权 MySQL/StarRocks 业务库证书、只读账户、真实 SQL 端到端验证及故障恢复演练，**生产状态仍 NO-GO**。
