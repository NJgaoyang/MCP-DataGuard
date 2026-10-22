# Native authentication migration (no Keycloak)

MCP DataGuard now uses its own PostgreSQL-backed user sessions and Agent tokens.
The web app signs in directly through `POST /api/v1/auth/login`.
No OIDC authority, redirect URI, or Keycloak container is required in the
native deployment.

## First login

An administrator supplies a private temporary-password file on the server,
then runs the **offline** `bin/bootstrap-native-admin.py` tool under the
`dg_platform` PostgreSQL role. The file must be owner-only (`chmod 600`).
No default password is baked into the application, container, or Git.

The script can enroll the existing `admin` account with no native password
and attach its existing `org-admin` role. It will not overwrite an existing
native password. After first login, the backend permits only
`POST /api/v1/auth/password` and `POST /api/v1/auth/logout` until the
temporary password is replaced by a 12+ character complex password.
All existing sessions are invalidated at password change.


## 服务器端首次初始化（一次性）

在服务器 SSH 终端执行下列步骤；**不要把临时密码放到命令参数、
Git、聊天、环境文件或 shell 历史中**。此脚本不会覆盖已有本地密码。

```bash
cd /opt/mcp-dataguard
umask 077
read -r -s -p "请输入一次性初始密码：" FIRST_PASSWORD
printf '\n'
printf '%s\n' "$FIRST_PASSWORD" > /root/dataguard-first-password.txt
unset FIRST_PASSWORD

set -a
source deploy/.env.dev
set +a
export PGHOST=127.0.0.1 PGPORT="${PG_HOST_PORT:-55432}" PGDATABASE=dataguard
export PGUSER="${PG_PLATFORM_USER}" PGPASSWORD="${PG_PLATFORM_PASSWORD}"
python3 bin/bootstrap-native-admin.py \
  --org dataguard --username admin \
  --password-file /root/dataguard-first-password.txt
unset PGPASSWORD
rm -f /root/dataguard-first-password.txt
```

建议初始密码采用独立随机强密码；即使旧测试约定使用过
`123456`，也不要把众所周知的弱密码开放在公网。初次登录后，
只能先修改为至少 12 位、含大小写字母、数字和符号的复杂密码，
完成后之前的会话全部失效。使用新密码重新登录，才可正常管理系统。

**已存在的老 OIDC 用户可保留**：离线脚本会为该组织中尚未设置
本地密码的 `admin` 配置本地密码，保留原 `org-admin` RBAC 权限。

## API lifecycle

`POST /api/v1/auth/login`: input
`{"organization":"dataguard","username":"admin","password":"..."}`.
Returns an **opaque** 256-bit bearer token and `mustChangePassword`.
The frontend keeps it only in memory and never saves it in localStorage.

`POST /api/v1/auth/password`: body `currentPassword` and `newPassword`.
`POST /api/v1/auth/logout`: revoke current token.
Org administrators create further accounts with a strong initial password
using `POST /api/v1/admin/users`; those accounts must change it at first login.

`POST /api/v1/agents/{agentId}/native-token`: mint a 30-day random agent
bearer (shown once; issuing again revokes existing tokens).
`POST /api/v1/agents/{agentId}/native-token/revoke`: revoke.
SQL execution is still disabled independently from token authentication.

## Deployment precautions

Apply Flyway V3 before provisioning accounts.
Retain a rollback backup of the PostgreSQL platform schema.\nIn an upgrade, verify native login and forced password change before deleting\nlegacy identity-provider data volumes.

**Security follow-ups:** implement rate limiting outside PostgreSQL row locks,
audit login failures without revealing secrets, restrict token creation to
fine-grained agent permissions, and implement secured HttpOnly cookie + CSRF
when persistent browser sessions are desired.
