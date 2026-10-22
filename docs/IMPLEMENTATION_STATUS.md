# MCP DataGuard dev20261022 研发状态

> 本分支**不是**已经完成所有功能的数据库安全网关。不要连接生产业务库。

## 已进入 GitHub 源码

- Java 21 / Spring Boot 4、Spring AI MCP Server、PostgreSQL 17 平台管理库 / Flowable 8 双 Schema。
- Flyway V1 包含原始 44 张 PostgreSQL 平台业务表。
- 六个 MCP Tool 已有注解入口，并且默认拒绝请求，不执行任何 SQL。
- SQL 简单结构词法检查只用于识别/拒绝，**永远不独立提供 ALLOW 决策**。
- JWT iss / aud 校验基础配置、Flowable 两级审批 BPMN 模板、Maven CI。

## 尚未完成的关键功能

- 用户与 Agent 身份数据库注册映射、租户权限、数据源注册和密文凭据管理。
- ShardingSphere 方言 AST 与安全语义完整认证、只读 JDBC 执行器及数据脱敏。
- Flowable 可信审批与工单持久化、审批人与申请人回避校验。
- 独立写入执行器、授权摘要、未知状态 UNKNOWN 故障处理。
- Vue3 Web 全量管理页面、完整审计、监控告警与安装升级。
- 真实 PostgreSQL 建库、Flowable 实例以及 MySQL/PG 业务库端到端验证。

**任何语法检查都不能替代 SQL 权限校验和物理只读数据库账户。**
