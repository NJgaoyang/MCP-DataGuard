CREATE TABLE sql_policies (
  id CHAR(36) PRIMARY KEY,
  code VARCHAR(120) NOT NULL UNIQUE,
  name VARCHAR(200) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  latest_version INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_sql_policy_status CHECK(status IN ('DRAFT','ACTIVE','DISABLED')),
  CONSTRAINT fk_sql_policy_user FOREIGN KEY(created_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sql_policy_versions (
  id CHAR(36) PRIMARY KEY,
  policy_id CHAR(36) NOT NULL,
  revision INT NOT NULL,
  priority INT NOT NULL,
  decision VARCHAR(24) NOT NULL,
  table_name VARCHAR(255) NOT NULL,
  environment VARCHAR(16) NOT NULL,
  operation VARCHAR(16) NOT NULL,
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_by CHAR(36) NULL,
  published_at DATETIME(3) NULL,
  UNIQUE KEY uk_policy_revision(policy_id,revision),
  INDEX idx_active_policy_rule(published_at,environment,table_name,operation,priority),
  CONSTRAINT chk_sql_policy_decision CHECK(decision IN ('DENY','REQUIRE_APPROVAL')),
  CONSTRAINT chk_sql_policy_operation CHECK(operation IN ('SELECT','UPDATE','DELETE','INSERT')),
  CONSTRAINT chk_sql_policy_environment CHECK(environment IN ('DEV','TEST','STAGING','PROD')),
  CONSTRAINT fk_sql_policy_version_policy FOREIGN KEY(policy_id) REFERENCES sql_policies(id),
  CONSTRAINT fk_sql_policy_version_creator FOREIGN KEY(created_by) REFERENCES app_users(id),
  CONSTRAINT fk_sql_policy_version_publisher FOREIGN KEY(published_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
