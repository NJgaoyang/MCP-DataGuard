# 首次管理员初始化（内置认证）

已废弃 OIDC Subject 注册流程，不再需要 Keycloak 管理后台。
使用 `bin/bootstrap-native-admin.py` 在服务器本机初始化 `admin`
账户与 `org-admin` 角色。详细的安全命令参见 [NATIVE_AUTH.md](NATIVE_AUTH.md)。

**不要使用旧版 `bin/bootstrap-admin.py` 进行新的身份初始化。**
