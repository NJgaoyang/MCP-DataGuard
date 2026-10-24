# 设计需求与当前 MySQL 单体实现对照

核对日期：2026-10-24。核对对象为当前工作区，包括尚未提交的单体功能增量，不限于 Git HEAD。

本报告回答“按照 docs 中的设计，哪些功能尚未实现”。它与 `STANDALONE_RELEASE_REVIEW_20261024.md` 的上线风险检查用途不同；不将页面存在、配置保存或历史测试通过直接视为设计需求完成。

## 一、采用的设计依据与范围变化

1. `PRD.md` 的 FR-01～FR-56 是完整产品需求基线，`DEVELOPMENT_BACKLOG.md` 将其映射到 DG-01～DG-56；`TECHNICAL_DESIGN.md` 和 `contracts/` 补充接口、权限、审批、执行与审计细节。
2. `STANDALONE_MYSQL.md` 是当前 MySQL、单企业、JAR + systemd 的部署与运行基线。根 `pom.xml` 只构建 `dataguard-standalone`，因此历史目录里存在的实现不能计为当前单体功能已交付。
3. 后续已调整的范围：平台管理库改为 MySQL，不依赖 PostgreSQL 平台库；使用内置认证，不依赖 Keycloak/OIDC；单企业不实施多组织；采用单体 JAR，不要求旧 Docker/Gateway/Workflow 拆分部署；手册明确排除本次备份及恢复实施。
4. 必须区分“平台管理库”与“目标业务库”。平台改成 MySQL，不等于 PRD 中 PostgreSQL 业务数据源支持已经完成或已正式取消。
5. 原 PRD 明确包含 SQL 变更申请、审批后执行、写入未知态对账。当前单体手册仅提供只读基线，未提供覆盖 FR-01～56 的正式需求变更矩阵，因此这些能力应记录为“原设计未交付”，不能直接标为已取消。
6. FR-35 的独立写进程与当前单体部署约束有冲突。若保留原写能力，需要先重定执行与凭据隔离设计，不能直接恢复旧网关/Worker 部署来代替当前单体要求。

状态说明：“部分”表示实现了子集，但未达到整条设计需求；“替代实现”表示后续改变了实现方式；“未实现”包括旧模块有代码但未接入当前单体；本报告不以未执行的验收推断功能不存在，也不把代码存在当成已经验收。

## 二、FR-01～FR-56 逐项对照

| 编号 | 原设计功能 | 当前单体状态 | 尚缺内容或范围说明 | 证据 |
|---|---|---|---|---|
| FR-01 | 组织空间与隔离 | 范围调整 | 当前为单企业，不把组织创建、多租户功能列为本次单体缺陷；PRD 需同步标明 | D1、C1 |
| FR-02 | 人员身份、登录限制、会话撤销 | 替代实现且部分 | 内置 BCrypt、首次改密和账户锁定已有；全会话撤销和敏感操作确认未完整实现；OIDC 已替换 | C2 |
| FR-03 | 角色与数据权限 | 部分 | 固定三角色及表/列白名单已有；缺自定义角色、权限矩阵、角色分配修改、逐 Agent/工具/数据源授权和有效权限解释 | C2、C3 |
| FR-04 | 申请人、Agent、审批人与执行职责分离 | 部分 | 单人读取申请禁止自批；缺独立 Agent 与委托人责任记录，以及原设计写执行凭据隔离链路 | C3、C6、C7 |
| FR-05 | Agent 资产注册与生命周期 | 部分 | 只有用户名下 token 标签、到期和撤销；缺独立 Agent 资产、说明、用途、允许客户端、独立启停、编辑和管理视图 | C3 |
| FR-06 | 机器身份认证 | 替代实现 | 已有摘要保存的随机 token、有效期与撤销；无需恢复 JWT/OIDC 依赖，但缺 Agent 资产绑定、凭据轮换完整流程与兼容验收 | C3 |
| FR-07 | 逐 Agent 工具与对象授权 | 未实现 | token 继承所属用户的读取审批；不能独立配置工具、数据源、表列范围和变更申请权限 | C3、C4 |
| FR-08 | 安全配额 | 部分 | 有固定行数与部分 JDBC 超时；缺按 Agent 的速率、并发、返回字节、总耗时、每日申请额度及配置管理 | C4 |
| FR-09 | 完整数据源 CRUD | 部分 | 有 MySQL 创建、开关；缺编辑、负责人、标签、环境、数据库类型选择与连接选项完整管理 | C5 |
| FR-10 | 凭据安全与生命周期 | 部分 | 有只读密码环境变量引用且不回显；缺轮换流程、外部 Secret Provider/加密托管和写凭据隔离实现 | C5、C4 |
| FR-11 | 连通性与最小权限检测 | 部分 | 测试仅 REQUIRED + SELECT 1；未共用真实读取要求的 VERIFY_IDENTITY、主机允许范围及只读 GRANT 校验，也不区分网络/认证/TLS/权限结果 | C5、C4 |
| FR-12 | 元数据服务 | 部分 | get_schema 仅返回已授权表名；缺库/Schema/列、类型、索引、元数据缓存及过期机制 | C4 |
| FR-13 | 数据库能力声明与认证 | 未实现 | 没有数据源插件能力矩阵、认证版本和未认证能力禁用模型；单体读取只实现 MySQL | C1、C4、C5 |
| FR-14 | MCP 数据源发现 | 部分 | 已过滤用户有效审批与白名单，且不输出连接秘密；缺设计要求的能力声明、筛选与游标 | C3、C4 |
| FR-15 | MCP Schema 工具 | 部分 | 有表名发现；缺列级元数据、筛选和分页，不能视为完整 Schema 服务 | C3、C4 |
| FR-16 | 参数化 SQL 只读执行 | 部分，入口收窄 | 当前接收 sourceId/tableName/limit，由服务端固定构造 SELECT；没有设计中的 sql/parameters 分析入口，也缺列类型、截断标记、请求 ID 等回执 | C3、C4 |
| FR-17 | check_sql 工具 | 未实现 | MCP 没有 SQL 预检、操作/对象/风险识别与统一安全决策输出 | C3、C8 |
| FR-18 | submit_change/get_change_status | 未实现 | MCP 仅三个工具，没有参数化变更 SQL 申请、工单与执行状态查询；现有“申请读取”不能替代 | C3、C6 |
| FR-19 | 方言 AST 与安全上下文 | 未迁移 | 单体未接入旧 SQL 核心或 ShardingSphere 分析链路；当前固定查询的窄入口不等于完成通用 SQL 分析 | C1、C4、C8 |
| FR-20 | SQL 操作与副作用识别 | 未迁移 | 缺设计中的 JOIN/CTE/子查询/导出/调用/动态执行等分析上下文；当前不接受任意 SQL，不能因此宣称这些语法已认证 | C4、C8 |
| FR-21 | 多维风险规则 | 部分 | 草稿仅有表、环境、操作、判定、优先级；缺 Agent、数据源、字段、WHERE、敏感级别、时间及影响范围等条件 | C8 |
| FR-22 | 统一决策顺序与解释 | 部分 | 白名单和个人审批已有；已发布 SQL 规则未进入实际读取判定，没有设计的统一 PolicyEngine 链路及命中版本回执 | C4、C8 |
| FR-23 | 策略全生命周期 | 部分 | 有草稿、版本、模拟、DENY 发布及停用；缺回滚、版本差异、完整规则验证和生产修改复核；REQUIRE_APPROVAL 不能发布生效 | C8 |
| FR-24 | 完整只读管控 | 部分 | 有 GRANT 检查、固定有界查询、默认遮盖与字段展示授权；缺按角色脱敏、字节预算、并发控制、必要列投影与资源认证 | C4 |
| FR-25 | Explain 与影响评估 | 未实现 | 没有执行计划、估算影响行数、风险展示或估算能力声明 | C4、C8 |
| FR-26 | 危险 SQL 基线 | 部分，入口收窄 | 当前不给任意 SQL/写入入口；原设计对多方言危险语法、无 WHERE 写入等的分析和对抗用例未迁移 | C4、C8 |
| FR-27 | 可视化 BPMN 设计与验证 | 部分 | 只有线性节点表单与高风险末节点标记；缺 bpmn-js、网关/会签/计时器等设计能力及运行部署，节点角色未绑定真实审批主体 | C7、C11 |
| FR-28 | 流程版本绑定 | 部分 | 保存草稿/发布/历史元数据；实际申请仍启动固定流程，没有绑定界面发布版本，也缺模板停用生命周期 | C6、C7 |
| FR-29 | 风险规则选择审批模板 | 未实现 | 没有按数据源、环境、风险、SQL 类别选择流程版本和默认兜底映射 | C6、C7、C8 |
| FR-30 | 完整任务中心 | 部分 | 仅待办、同意、拒绝及意见；缺已办、我发起、详情、转办/委托、退回、撤回、附件和历史轨迹界面 | C6、C11 |
| FR-31 | 审批一致性 | 部分 | 单个指定审批人的自批禁止、角色验证和 FOR UPDATE 已有；未实现设计中的多阶段/会签一致性、不可变 SQL 快照和参数绑定 | C6 |
| FR-32 | 时效、提醒与升级 | 部分 | 读取/决策会校验固定 24 小时有效期；缺到期状态清理、计时任务、提醒、超时升级及通知 | C6 |
| FR-33 | 审批后执行联动 | 未实现 | 读取审批仅生成表访问授权；缺 SQL 变更授权任务、Outbox、队列与独立执行状态，无法完成设计的审批后变更链路 | C6、C12 |
| FR-34 | 安全只读执行器 | 部分 | 有 MySQL JDBC 实际读取与错误处理；缺设计的按源连接池、字节和并发资源约束及完整回执；PG 业务读取未迁移 | C4 |
| FR-35 | 安全写入执行器 | 未迁移且架构待调整 | 当前单体无受控写执行；旧 CertifiedWriteWorker 不参与构建。独立进程要求与单体约束的冲突需重新形成设计决议 | C1、C12 |
| FR-36 | 精确执行授权 | 未实现 | 没有绑定 SQL/参数摘要、策略/流程版本、目标、主体、有效期与次数的变更授权票据及执行前复核 | C6、C12 |
| FR-37 | 幂等、租约与 UNKNOWN | 未迁移 | 缺变更任务领取、fencing、自动尝试次数限制、提交未知态与人工对账链路 | C12 |
| FR-38 | 执行回执与取消 | 部分 | 读取返回行数并记录耗时；缺统一 request/execution ID、开始/完成时间、事务状态、影响行数与执行记录详情；无待执行任务取消 | C4、C12 |
| FR-39 | 请求证据链 | 部分 | 有用户名、资源、动作、结果、耗时；缺 Agent 独立身份、request/trace ID、SQL 指纹、规则版本和完整链路关联 | C3、C9 |
| FR-40 | 完整审批审计 | 部分 | 有申请/批准/拒绝操作日志；缺任务领取、转办、阶段、流程版本和最终执行关系的结构化历史 | C6、C9 |
| FR-41 | 审计检索与导出 | 部分 | API 只支持 SQL_READ 过滤及最近 200 条；缺服务端分页、时间/Agent/用户/数据源/工单等组合查询和授权脱敏导出 | C9、C11 |
| FR-42 | 实时监控 | 部分 | 有五分钟聚合与十四天趋势，页面手动刷新；缺 P99、工具/慢查询/执行器/连接池指标，现有拒绝数、P95、过期待办口径需校正 | C9、C11 |
| FR-43 | 安全告警闭环 | 部分 | 有读取拒绝/错误事件、筛选、确认、解决；缺阈值规则、聚合去重、静默恢复、积压检测与 Webhook/邮件通知 | C9 |
| FR-44 | 审计防篡改证据 | 未迁移 | 缺追加写权限边界、审计哈希链、周期签名与外部锚定；流程 JSON 的校验摘要不能代替审计证据链 | C9、C12 |
| FR-45 | 实际业务首页 | 未实现 | 概览目前是三张说明卡片；没有业务总览、异常 TOP、近期活动、待办汇总和环境筛选 | C11 |
| FR-46 | 完整统一管理工作台 | 部分 | 核心菜单与部分表单已有；缺多数详情、编辑、回滚、权限可见性和完整操作闭环 | C5、C8、C11 |
| FR-47 | SSE/WebSocket 实时状态 | 未实现 | 审批进度、监控与告警没有流式订阅或断线恢复机制 | C11 |
| FR-48 | 国际化与可访问性 | 部分且待验收 | 中文与响应式样式已有；缺英文资源体系、浏览器兼容、键盘操作及真实移动审批的完成证据 | C11 |
| FR-49 | 版本化 REST/OpenAPI | 部分 | 单体 API 是 /api；现有 OpenAPI 是旧版 /api/v1 草案，与新接口不匹配；缺统一错误/request ID、分页过滤、幂等键与限流 | C10 |
| FR-50 | Webhook 事件投递 | 未迁移 | 当前单体无审批/执行/策略/告警领域事件的订阅、签名、重试、死信和事件去重体系 | C1、C9、C12 |
| FR-51 | 数据库 SPI/SDK | 未实现于单体 | 数据源连接和查询硬编码 MySQL；缺可加载插件接口、标准认证包、示例插件与兼容版本契约 | C1、C4、C5 |
| FR-52 | 可复现安装交付 | 替代实现且部分 | JAR、systemd、MySQL 迁移和首次管理员初始化已有；旧 Compose 不必恢复，缺示例业务数据源/Agent、完整演示与干净版本化交付核验 | C1、C13 |
| FR-53 | 生产运维 | 部分，备份另行排除 | 有 systemd、部分 TLS 与生产启动门禁；缺单体健康/就绪检查、日志与容量治理、离线依赖交付、升级回退演练；备份不列入本次缺项 | D1、C13 |
| FR-54 | 完整文档资产 | 部分 | 有大量设计与历史文档；缺统一的当前单体用户/开发/API/MCP/升级/兼容说明，且多份文档互相冲突；没有独立双语 README | D2、C13 |
| FR-55 | 开源交付及供应链 | 部分 | 有 SECURITY.md 和旧镜像扫描/SBOM 工作流；当前仓库未见 LICENSE/NOTICE，CONTRIBUTING 仍写许可证待定；缺本单体最终 JAR 的对应 SBOM/扫描发布证据 | C14 |
| FR-56 | 社区贡献与兼容政策 | 部分 | 有简要 CONTRIBUTING 和技术设计内 ADR 表；缺 Issue/PR 模板、行为准则、正式版本/弃用政策及可执行插件准入流程 | C14 |

## 三、证据索引

- D1：`STANDALONE_MYSQL.md:3`、`:5`、`:73`，当前架构与范围变化；不是原 PRD 全功能完成声明。
- D2：`WEB_UI.md:1`、`MANAGEMENT_API_STATUS.md:12`、`DEVELOPMENT_BACKLOG.md:3`、`IMPLEMENTATION_STATUS.md:3`，分别仍描述 OIDC、早期未实现状态、全量任务初始状态或旧 PG 分支事实。
- C1：根 `pom.xml` 的 modules 与 `dataguard-standalone/pom.xml`，默认构建只包含单体模块；旧 server/sql-core/workflow 目录保留。
- C2：`dataguard-standalone/.../AuthSecurity.java`、`ForcedPasswordFilter.java`、`UsersApi.java`，内置认证、固定角色、改密和重置。
- C3：`dataguard-standalone/.../AgentTokens.java`、`AgentMcpApi.java`，token 生命周期、身份解析和 MCP 工具声明。
- C4：`dataguard-standalone/.../ReadApi.java`、`MysqlReadGrantPolicy.java`，固定表查询、字段展示授权、资源预算与物理权限校验。
- C5：`dataguard-standalone/.../DatasourceApi.java`，配置字段、连接测试与白名单管理。
- C6：`dataguard-standalone/.../ApprovalApi.java` 及 `processes/internal-read-approval.bpmn20.xml`，固定单人读取审批。
- C7：`dataguard-standalone/.../WorkflowDesignApi.java`，线性节点设计及元数据发布；返回 flowableDeploymentEnabled=false。
- C8：`dataguard-standalone/.../SqlPolicyApi.java`，规则草稿与检查接口；有效判定返回 simulationOnly=true；单体没有 SQL Parser 调用链。
- C9：`dataguard-standalone/.../AuditService.java`、`OperationsApi.java`、`SecurityAlertsApi.java` 与 V6 迁移，当前日志、监控与告警模型。
- C10：`contracts/rest/admin-openapi.yaml`、`contracts/mcp/mcp-tools-v1.json` 对照新单体控制器，旧契约不等于当前单体实现。
- C11：`dataguard-web/src/StandaloneApp.vue`、`dataguard-web/package.json` 和 `design-qa.md`，当前 UI、依赖与未完成的视觉交互验收。
- C12：旧 `dataguard-server` 的 CertifiedWriteWorker、ApprovedExecutionQueue、ExecutionTicketSigner、ExecutionRecoveryWatchdog、ExecutionReconciliationController、MaskRuleController，以及旧 `dataguard-workflow` 的审批 Outbox/超时组件；这些不参与当前单体构建。
- C13：`bin/build-standalone.sh`、`bin/install-standalone-systemd.sh`、`.github/workflows/standalone-mysql.yml`、`application.yml` 与 MysqlProductionGate，当前构建、部署和基础验收。
- C14：根目录文件和 `.github` 清单；`CONTRIBUTING.md:2` 标记许可证元数据待定；`mandatory-security-gates.yml` 的供应链矩阵指向旧 gateway/workflow/web 镜像，不包含单体 JAR 发布。

## 四、需要同步修订的设计文档

| 文档 | 当前问题 | 应如何处理 |
|---|---|---|
| PRD.md / TECHNICAL_DESIGN.md | 仍以 PG 平台、组织和旧服务拓扑为基础，缺单体需求变更记录 | 保留原始目标，追加当前单体适配决议、目标业务库范围及 FR 映射 |
| DEVELOPMENT_BACKLOG.md | 开头仍声称全部未实现，不能反映近期功能 | 用本报告逐条更新任务状态，分别记录实现、测试与发布状态 |
| WEB_UI.md / MANAGEMENT_API_STATUS.md | OIDC 和早期只读清单已不对应当前页面与 API | 标历史版本或改写为当前单体接口/界面说明 |
| IMPLEMENTATION_STATUS.md / CORE_SECURITY_ACCEPTANCE.md | 记录 dev20261022 的 PG/Worker 验证，容易误当单体验收 | 明确历史适用范围；独立建立 MySQL 单体的验证记录 |
| DATABASE_DESIGN.md | PG 44 表与双 Schema 不是当前 MySQL 模型 | 补充单体 V1～V6 的结构、关系、状态机与迁移设计 |
| PRODUCTION_* / HARDENED_DEV_DEPLOY_EVIDENCE.md | 多为旧 Docker/PG 部署，且备份范围与单体手册冲突 | 按版本明确适用架构和范围，不将旧门禁脚本作为新单体完成证据 |
| NATIVE_AUTH.md / BOOTSTRAP_ADMIN.md / DELIVERY_HANDOVER.md | 旧离线 PG 初始化、opaque 人员会话与当前 Session 登录不同 | 建立当前单体认证与运维接手说明，避免混用初始化工具 |

## 五、功能补齐顺序

1. 先把当前单体的基础业务闭合：身份/会话、Agent 独立授权与配额、数据源编辑与元数据、读取结果与脱敏、授权撤回。
2. 接入 SQL 分析与统一策略判定，完成规则版本回滚和 Explain，确保 Web 与 MCP 共用同一条安全链路。
3. 完成真实流程设计部署、模板映射、版本绑定、完整任务中心和时效通知。
4. 按原设计目标实施 check_sql、submit_change、get_change_status 与受控变更执行；写执行边界须先适配单体约束，再实现精确授权、幂等、UNKNOWN 与对账。
5. 补齐审计证据链、检索导出、监控告警通知、真实首页、实时状态、版本化接口、插件契约和开源发布文件。

如本次只交付“单企业 MySQL 有界只读首版”，应在 PRD 中明确延期的 FR，而不是把原有写执行、多数据库业务支持等目标直接计为完成。
