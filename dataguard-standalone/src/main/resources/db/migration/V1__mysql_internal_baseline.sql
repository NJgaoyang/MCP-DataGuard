-- MySQL 8.4+, utf8mb4. Internal single enterprise; no organization table.
-- Only first startup with zero users bootstraps admin/123456 with must_change_password=true.
CREATE TABLE app_users (
  id CHAR(36) PRIMARY KEY COMMENT '系统用户 UUID',
  username VARCHAR(64) NOT NULL UNIQUE COMMENT '登录用户名',
  display_name VARCHAR(100) NOT NULL COMMENT '姓名',
  password_hash VARCHAR(120) NOT NULL COMMENT 'BCrypt 摘要，禁止明文存储',
  role_code VARCHAR(16) NOT NULL COMMENT 'ADMIN、APPROVER、READER',
  enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '停用后不可登录、审批',
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE COMMENT '首次登录强制修改',
  failed_login_count INT NOT NULL DEFAULT 0,
  lock_until DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_app_user_role CHECK(role_code IN ('ADMIN','APPROVER','READER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业内部多用户';

CREATE TABLE data_sources (
  id CHAR(36) PRIMARY KEY,
  code VARCHAR(64) NOT NULL UNIQUE,
  display_name VARCHAR(128) NOT NULL,
  host VARCHAR(253) NOT NULL,
  port INT NOT NULL DEFAULT 3306,
  database_name VARCHAR(64) NOT NULL,
  read_username VARCHAR(80) NOT NULL,
  password_env VARCHAR(100) NOT NULL COMMENT '环境变量名称，不存储目标库明文密码',
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_target_port CHECK(port BETWEEN 1 AND 65535)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='受控 MySQL 只读数据源';

CREATE TABLE read_table_allowlist (
  id CHAR(36) PRIMARY KEY,
  source_id CHAR(36) NOT NULL,
  table_name VARCHAR(64) NOT NULL COMMENT '显式授权 SELECT 的表',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_source_table(source_id,table_name),
  CONSTRAINT fk_allowed_source FOREIGN KEY(source_id) REFERENCES data_sources(id),
  CONSTRAINT fk_allowed_user FOREIGN KEY(created_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正向白名单，默认拒绝所有表';

CREATE TABLE approval_requests (
  id CHAR(36) PRIMARY KEY,
  requester_user_id CHAR(36) NOT NULL,
  approver_user_id CHAR(36) NOT NULL COMMENT '必须引用系统中真实用户',
  source_id CHAR(36) NOT NULL,
  table_name VARCHAR(64) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  process_instance_id VARCHAR(128) NULL,
  decided_at DATETIME(3) NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_approval_status CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
  CONSTRAINT fk_req_requester FOREIGN KEY(requester_user_id) REFERENCES app_users(id),
  CONSTRAINT fk_req_approver FOREIGN KEY(approver_user_id) REFERENCES app_users(id),
  CONSTRAINT fk_req_source FOREIGN KEY(source_id) REFERENCES data_sources(id),
  INDEX idx_approver_inbox(approver_user_id,status,created_at),
  INDEX idx_requester_grants(requester_user_id,source_id,table_name,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统内用户 Flowable 审批';

CREATE TABLE audit_logs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  actor_user_id CHAR(36) NULL,
  actor_username VARCHAR(64) NOT NULL,
  action VARCHAR(80) NOT NULL,
  resource_type VARCHAR(60) NOT NULL,
  resource_id VARCHAR(100) NOT NULL,
  outcome VARCHAR(20) NOT NULL,
  detail VARCHAR(1024) NULL,
  INDEX idx_audit_date(event_time),
  INDEX idx_audit_actor(actor_username,event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计，禁止写入口令和查询结果';