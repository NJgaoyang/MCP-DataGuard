# Agent 与数据源管理 API（开发版）

## 已实现的业务路径

- `POST /api/v1/agents`：要求 `agent:write`；仅以当前已登记用户的 `org_id`、`user_id` 设置归属，创建 `DISABLED` Agent。
- `GET /api/v1/agents/{id}/edit`、`PUT /api/v1/agents/{id}`：编辑当前租户 Agent，需 `agent:write`。变更要求 `expectedVersion`，CAS 检查可阻止覆盖其他人的修改。
- `POST /api/v1/agents/{id}/revoke`：原子撤销 Agent，连同已有的 Agent 身份一并撤销，避免新请求再通过校验。
- `POST /api/v1/datasources`：要求 `datasource:write`，仅登记 MySQL/PostgreSQL 数据源配置，默认 `DISABLED`；不存储密钥、不会进行 JDBC 连接。
- `GET /api/v1/datasources/{id}/edit`：仅写权限角色可读取包括 host、port 的可编辑配置，不返回密钥。
- `PUT /api/v1/datasources/{id}`：仅允许更新停用数据源的连接配置，必须匹配 `expectedVersion`。
- `POST /api/v1/datasources/{id}/disable`：停用目标数据源。
- 成功变更与 `dg_system_event` 操作日志在同一数据库事务完成，避免出现已修改但完全无操作轨迹的状态。

## 安全边界

数据库密码/连接密钥不接受在上述 DTO 中提交。创建数据源、注册 Agent 并不会自动赋予任何 SQL 读取或写入权限。新记录始终为 DISABLED；要进行 SQL 查询，还必须另行完成可信身份注册、对象权限、数据库插件认证、密钥提供者、物理最小权限账户以及严格的 SQL 风控。

本阶段审计仅记录操作、操作者、组织与资源 ID，不是完整的不可篡改 SQL 审计链。数据源地址在后续开放联通性测试前须加入 DNS 重绑定/SSRF 和网络出口白名单保护。安装程序仍缺少首次超级管理员引导流程。

## 前端

Vue3 Agent/数据源管理页面已连接新增、编辑、撤销/停用接口。用户既要具备读权限查看列表，又要具备写权限修改。后端错误 400/403/404/409 分别表示输入错误、禁止、未找到、版本或状态冲突。前端的表单错误提示后续继续细化。
