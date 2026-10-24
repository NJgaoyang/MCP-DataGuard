# dev202610241 · MCP AI Agent 接入实施台账

更新基线：2026-10-24。对应仓库的 MySQL 单体模块 `dataguard-standalone`，**不包括未参与默认构建的 PostgreSQL 旧模块**。

> 判定规则：已提交 = 有明确源码和 commit；已验证 = 有可复现的自动化/运行证据；生产完成 = 在目标环境完成真实业务联调及安全签署。三者不能混同。

## 本轮实际提交

| 项目 | 状态 | 位置 / 证据 |
|---|---|---|
| MCP `describe_table` 工具声明和派发 | 已编码，未运行验证 | `AgentMcpApi.java` |
| 只暴露已审批且列级放行的字段名 | 已编码，未运行验证 | `ReadApi.mcpColumns` |
| MCP `list_datasources` / `get_schema` / `execute_readonly` | 原有实现，未重新验收 | `AgentMcpApi.java` |
| Agent Token 签发、到期、撤销和 SHA-256 摘要存储 | 原有实现，未重新验收 | `AgentTokens.java` |
| 表白名单、Flowable 表级审批、默认字段脱敏 | 原有实现，未重新验收 | `ReadApi.java` / `ApprovalApi.java` |
| 基础访问审计 | 原有实现，未重新验收 | `AuditService.java` |

## 未实现或未达到预定验收要求

| 等级 | 能力 | 缺口与完成标准 | 状态 |
|---|---|---|---|
| P0 | 安全自定义 SELECT | 现有 `execute_readonly` 仅以服务端固定的 `SELECT * FROM table LIMIT n` 读取；需要先选定可维护的 SQL AST 解析器，按 MySQL 方言校验引用表、列、子查询、JOIN、函数和参数，执行时强制只读数据库账户、时限、行数与字节限制；完成穿透/越权/拒绝测试 | 未实现 |
| P0 | Agent 独立授权 | 当前 Token 继承 owner 用户可读表审批，不存在独立的 Agent-to-source/table/column ACL；需要默认拒绝、单独授权、即时撤销、用户与 Agent 权限取交集及审计 | 未实现 |
| P0 | SQL 审批闭环 | 当前 Flowable 审批绑定表而非 SQL 指纹和不可变执行上下文；需审批后重验、一次性授权/到期、拒绝及撤销场景测试 | 未实现 |
| P0 | SQL 审计 | 需记录 agent ID、request ID、SQL 指纹、数据源、审批 ID、查询成本、脱敏结果数量、失败原因，且不存敏感明文 | 未实现 |
| P0 | 跨客户端 MCP 合规 | 新服务为自建 HTTP JSON-RPC，尚无 Claude/Cursor 等客户端真实验证；补齐协议测试、HTTP 安全头、Origin 防护和初始化/通知/版本兼容性 | 未验收 |
| P0 | 真实 MySQL 8.4 及生产网络 | 主机、目标库授权、TLS、Flowable、并发授权撤销、告警及密钥等需现场联调 | 未验收 |
| P1 | 表字段完整结构 | 新 `describe_table` 只公开明确放行的字段名称，尚无字段类型/业务注释；只能在物理权限和敏感字段策略完成后受控公开 | 部分完成 |
| P1 | 结果分页及查询资源治理 | 当前每次最多 100 行、5 秒，缺少 cursor 语义、总结果字节限额及查询成本门禁 | 部分完成 |
| P1 | 跨数据库执行 | 当前默认只支持 MySQL；如果保留多数据库产品定位，需添加独立的目标库方言/执行器认证，**不得恢复 PostgreSQL 平台元数据库** | 未实现 |
| P1 | Web Agent 管理 | 当前为用户持有 Token 的列表/创建/撤销；缺 Agent 独立注册、授权、查询配额和 MCP 连接诊断页面 | 部分完成 |
| P1 | 端到端自动化 | 需要对授权、拒绝、Token 过期、字段撤销、审批拒绝及真实 MySQL 执行建立强制 CI | 未验收 |
| P2 | 标准授权发现 | 若服务通用第三方远程客户端，应实现符合 MCP 授权规范的 OAuth 资源发现及访问令牌验证 | 未实现 |

## 不在本轮范围

- 生产备份/恢复：根据已有范围决定明确不实施，作为残余风险披露。
- Agent 任意 SQL 写入：初期聚焦安全读取；INSERT/UPDATE/DELETE 必须独立的审批、事务、幂等与恢复设计。
- Docker：不作为 MySQL 单体部署方式。
- 多组织：MySQL 单体只支持单个企业内部用户。

## 下一步最小生产可验收场景

1. 在全新 MySQL 8.4 数据源创建只读账户，仅授权业务表的 SELECT。
2. 管理员设置表与可公开列白名单，内部真实用户发起 Flowable 审批并由另一授权用户批准。
3. Agent 使用独立凭证完成 `initialize`、`tools/list`、`list_datasources`、`get_schema`、`describe_table`。
4. 读取被允许的表并确认字段脱敏；未经批准的表、列、Token 均不能越权。
5. 完整自定义 SQL 实施以后，验证 SQL 拒绝、审批、重试、分页、资源限制和审计。
6. 对真实客户端和现网安全门禁提供可审查的测试报告。

**当前发布结论：NO-GO。** 本轮 GitHub 提交没有 Maven 编译、MySQL 集成环境和第三方 Agent 现场联调报告；禁止把未验证项记为完成。
