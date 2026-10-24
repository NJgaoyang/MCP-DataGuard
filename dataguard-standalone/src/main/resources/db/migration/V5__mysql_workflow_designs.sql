CREATE TABLE workflow_designs (
  id CHAR(36) PRIMARY KEY,
  code VARCHAR(100) NOT NULL UNIQUE,
  name VARCHAR(80) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_workflow_creator FOREIGN KEY(created_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_design_versions (
  id CHAR(36) PRIMARY KEY,
  workflow_id CHAR(36) NOT NULL,
  revision INT NOT NULL,
  steps_json LONGTEXT NOT NULL,
  graph_digest CHAR(64) NOT NULL,
  high_risk_only_last BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_by CHAR(36) NULL,
  published_at DATETIME(3) NULL,
  UNIQUE KEY uk_workflow_revision(workflow_id,revision),
  CONSTRAINT chk_workflow_version_status CHECK(status IN ('DRAFT','PUBLISHED','RETIRED')),
  CONSTRAINT chk_workflow_json CHECK(JSON_VALID(steps_json)),
  CONSTRAINT fk_workflow_version_design FOREIGN KEY(workflow_id) REFERENCES workflow_designs(id),
  CONSTRAINT fk_workflow_version_creator FOREIGN KEY(created_by) REFERENCES app_users(id),
  CONSTRAINT fk_workflow_version_publisher FOREIGN KEY(published_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
