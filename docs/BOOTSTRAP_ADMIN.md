# 首次 OIDC 管理员初始化

当 `dataguard` 数据库和平台 Flyway V1 迁移已经成功完成后，通过离线 CLI 创建第一组织和管理员。**没有匿名 Web 初始化 API，也没有默认管理员密码。**

## 环境变量

```bash
export PGHOST=127.0.0.1
export PGPORT=55432
export PGDATABASE=dataguard
export PGUSER=dg_platform
# 使用 .pgpass 或其他受限凭据来源提供密码，不要写入 shell 历史
```

## 初始化

```bash
python3 bin/bootstrap-admin.py \
  --org-code myorg \
  --org-name "My Organization" \
  --issuer "https://idp.example.com/realms/dataguard" \
  --subject "replace-with-exact-oidc-sub" \
  --username admin \
  --display-name "Organization Admin"
```

必须严格使用实际 JWT 的 `iss` 与 `sub`。CLI 使用与 Java 服务一致的长度前缀 SHA-256 构造身份摘要，并写入原始发行者与主题供运行时二次比对。运行脚本的数据库用户必须是受限平台角色而非超级管理员。

脚本只授予 `agent:read`、`agent:write`、`datasource:read`、`datasource:write`。它**不会**创建活跃 Agent 身份、不会授予 SQL 读写、不会测试或访问任何目标业务数据库。

注意：该命令是首次新组织 bootstrap，不是通用组织成员添加接口。多租户人员身份复用、授权更改和身份撤销需要后续专用管理工作流。若初始角色已经存在，数据库唯一约束会使事务失败，不会重复创建角色。
