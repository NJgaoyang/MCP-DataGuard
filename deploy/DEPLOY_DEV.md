# MCP DataGuard — isolated development deployment

仅供开发/测试使用。默认 SQL 真实执行与 Flowable Outbox 关闭，生产禁止使用本文件的浮动镜像、测试凭据或 HTTP 访问方式。

## 基本组件

- Java 21 Spring Boot Gateway、Spring AI MCP Server
- PostgreSQL 17，分别含 `platform`、`flowable` schema
- Vue 3 / Ant Design Vue 控制台
- **DataGuard 原生账号 + BCrypt + 服务端 Session**：不再需要 Keycloak、OIDC、外部登录回调

## 在授权测试机安装

```bash
git clone --branch dev20261022 https://github.com/NJgaoyang/MCP-DataGuard.git
cd MCP-DataGuard
cp deploy/.env.dev.example deploy/.env.dev
chmod 600 deploy/.env.dev
# 将默认占位符替换为仅用于测试环境的独立强口令
bash bin/check-dev-deploy.sh
bash bin/start-dev-deploy.sh
```

默认 PostgreSQL 和 Web 只暴露本机端口，公网测试时再使用经过批准的 `compose.public.yaml` 和 TLS。可以用 SSH 隧道进入：`ssh -L 18080:127.0.0.1:18080 operator@server`，通过 `http://127.0.0.1:18080` 访问原生登录界面。

以一次性管理员初始化脚本和受控改密流程建立账户；不得在 Git、日志或截图中公开密码。首次成功改密会撤销原 Session。

## 开发 Workflow（严格禁止默认触发真实写入）

```bash
docker compose --profile workflow --env-file deploy/.env.dev \
  -f deploy/compose.yaml -f deploy/compose.dev.yaml up -d workflow
```

默认 `FLOWABLE_OUTBOX_ENABLED=false`、`FLOWABLE_DYNAMIC_DEPLOYMENT_ENABLED=false`。数据库 schema 自动更新设置只适用于一次性开发数据库，生产模式固定禁止自动 DDL。

## 核查

```bash
docker compose --env-file deploy/.env.dev -f deploy/compose.yaml \
  -f deploy/compose.dev.yaml ps
curl -I http://127.0.0.1:18080/
curl -i http://127.0.0.1:18080/api/v1/me
```

未经认证的 `/api/v1/me` 返回 HTTP 401 是预期结果。验证角色与改密必须使用专属测试身份；切勿将已有生产管理员密码重置为公开默认值。

## 生产与发布请使用新手册

**不可将开发 Compose 直接作为生产模板。** 生产专属外部 TLS 数据库、受信证书、按镜像 digest 部署、root:10001/0640 文件密钥、安全限制、独立审批和回滚全部按 `docs/PRODUCTION_DEPLOYMENT.md` 执行；生产验收未签署时为 NO-GO。业务已确认不要求异地备份，但本机加密恢复和单机全损风险须有记录。
