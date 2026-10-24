# MySQL 单体版 · dev202610241

架构：一个 Spring Boot 4 / Java 21 JAR（内含 Flowable 工作流引擎、HTML 管理页面和审计接口），一个独立 MySQL 8.4 数据库；不需要 PostgreSQL、Keycloak、Docker Compose、多组织、网关 + 独立 Worker。

## 安全与业务规则
- 仅使用一个企业的内部用户，角色 ADMIN、APPROVER、READER。
- **空数据库首次启动**自动创建账号 `admin`，临时口令 `123456`。BCrypt 12 保存哈希，不保存明文；首次登录后除改密接口外不允许调用业务 API。新密码至少 12 位，大小写、数字、特殊字符，修改后立即失效旧 Session。下次启动不会重置任何现存密码。
- 管理员创建其他内部用户，初始强密码并强制首次改密。审批人只能从已启用、已改密、拥有 APPROVER 或 ADMIN 角色的系统用户列表中选择；不能自己审批。
- MySQL 数据源默认禁用。正向表白名单为空即拒绝所有读取；必须同时具备管理员授权白名单及本人已通过的内置 Flowable 审批。读取仅支持单表有界 SELECT，SQL 固定在服务端构造，默认全部字段脱敏；可在“数据源管理 → 允许字段”中由管理员逐一批准可显示的非敏感列，不暴露原始内容。数据库账号必须单独授予 SELECT。
- 管理员可查看审计日志。登录、改密、用户、数据源、表白名单、审批及读取均有操作日志。
- 旧 PostgreSQL 相关模块不包含在新的默认 Maven reactor；旧 Docker 配置保留在历史分支，不是此分支的安装方式。未实现的旧功能不得误标为已迁移。

## 本机安装（无 Docker）

### 1. MySQL 数据库

使用 MySQL 8.4+，建立 UTF8MB4 库并创建独立运行用户；生产环境必须限制来源 IP、启用 MySQL TLS、禁用匿名账号，确保只允许此应用访问元数据表。示意：

```sql
CREATE DATABASE dataguard CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'dataguard'@'127.0.0.1' IDENTIFIED BY 'GENERATE_YOUR_OWN_LONG_PASSWORD';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,CREATE VIEW,DROP ON dataguard.* TO 'dataguard'@'127.0.0.1';
```

生产应将 Flyway/Flowable 建表迁移权限与日常运行权限拆分；不要长期保留 DROP/ALTER。Flowable 初始化时需要迁移权限，运行时使用最低权限。示例仅用于首次初始化。

### 2. 编译与初始化

```bash
git checkout dev202610241
mvn -B -ntp -pl dataguard-standalone -am clean package
cp dataguard-standalone/target/dataguard-standalone-0.5.0-SNAPSHOT.jar /opt/dataguard/dataguard-standalone.jar
install -d -m 700 /etc/dataguard /var/lib/dataguard
# 根据 deploy/standalone.env.example 填入独立 MySQL 密码
chmod 600 /etc/dataguard/standalone.env
# 首次建 Flowable 表时由 DBA 临时设置 DATAGUARD_FLOWABLE_SCHEMA_UPDATE=true。
# 初始化成功之后立即恢复 false。
systemctl daemon-reload
systemctl enable --now dataguard-standalone.service
```

首次仅在本机 `http://127.0.0.1:8080/login.html` 登录 admin/123456，必须改成复杂密码并重新登录；正式对外应使用公司 HTTPS 反向代理，配置 DATAGUARD_COOKIE_SECURE=true 和可信代理/来源过滤。**禁止把临时口令登录界面直接发布公网。**

### 3. 只读认证

新增数据源的密码仅通过 `DATAGUARD_TARGET_..._PASSWORD` 在进程环境变量中提供，运行数据库账户只能 SELECT 被允许的数据表，数据库侧必须单独限制。已获准的表默认返回 REDACTED，若需要真实字段读取，必须经过专门字段授权/脱敏测试后才能开启；本版本不提供明文查询放行。

### 4. 旧数据迁移

这是**重新设计的 MySQL 元数据结构，不是 PostgreSQL 的逐表无损自动转换**。旧 PG 库原样保留，用户和审批记录如需迁移，必须做独立脱敏导出、ID/角色映射和总数核对，严禁复用旧密码明文或重放旧未知结果。生产迁移需要 DBA 验收。

### 5. 上线阻断

正式上线前必须通过真实 MySQL 8.4/Flowable 审批、登录锁定与 Session 撤销、并发权限收回、内网 TLS、审计留存和完整端到端检查。不要将“成功编译”视为生产 GO。
### 字段最小授权与审批到期

即使表在正向白名单里，读取也必须持有该**申请人自己**的未到期 Flowable 已批准记录。查询值默认全字段 `REDACTED`；只有 `read_column_allowlist` 中管理员明确批准的字段可显示（文本最多 256 字符，二进制和非安全类型仍保持脱敏）。撤销列授权、撤销表白名单后下一次读取即时生效。管理员不可直接通过变更请求者的用户名获得他人的审批权限。

### 无 Docker 的主机安装

已提供 `bin/install-standalone-systemd.sh`。在具有 Java 21 的授权 Linux 主机上，用受控 root 账号执行，它将创建低权限 `dataguard` 系统用户、复制独立 JAR 和 systemd unit，并生成仅 root 可读的配置模板（已存在的配置不会覆盖）。**不会自动启动或启用服务**，必须先由 DBA 配置数据库并检查环境文件。

```bash
bash bin/build-standalone.sh
sudo bash bin/install-standalone-systemd.sh
sudo chmod 600 /etc/dataguard/standalone.env
sudo systemctl enable --now dataguard-standalone
sudo systemctl status dataguard-standalone --no-pager
```

首次 Flowable 数据表初始化请由 DBA 在数据库变更窗口临时设置 `DATAGUARD_FLOWABLE_SCHEMA_UPDATE=true`，启动完成后立即恢复 false，后续不得让日常账户持有任意 DDL 权限。

### 用户确认：备份不属于本次实施范围

2026-10-24 决定：本项目**不实施备份/备份恢复功能或验收**，部署脚本不配置备份服务，后续开发计划不再列备份为必做项。此选择有 MySQL 数据丢失与故障后无法恢复的残余风险；运行与审计数据仍应设置必要保留、访问控制和磁盘监控。这一范围决定**不降低** TLS、最小权限、审批及审计要求。

### 目标 MySQL 强制只读权限

在执行任何表读取前，应用运行时执行 `SHOW GRANTS FOR CURRENT_USER()`。只允许 USAGE 和非全局 SELECT，拒绝 UPDATE、INSERT、DELETE、DDL、GRANT OPTION、ALL PRIVILEGES 及全局 SELECT。失败则拒绝查询。数据库管理员必须使用一个单独的 SELECT-only 账号，按白名单里的实际业务表授予 SELECT，不能复用 DataGuard 元数据管理账号。

### 无 Docker 的正式生产模式门禁

先在**仅本机可访问的初始化模式**完成 `admin/123456` 的首次强制改密。随后，数据库配置为 `sslMode=VERIFY_IDENTITY`（同时在 JVM 环境中配置可信 MySQL CA）、HTTPS 反向代理和 `DATAGUARD_COOKIE_SECURE=true`，继续保持 `DATAGUARD_BIND=127.0.0.1`；最后设置 `DATAGUARD_PRODUCTION=true`。缺少任何一项或默认管理员尚未改密，启动即拒绝进入生产模式。只读业务库连接也必须 VERIFY_IDENTITY，且独立账号通过 SHOW GRANTS 验证无写入权限。**备份已按本次需求明确排除。**
