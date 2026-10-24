-- Only explicitly disclosed columns may leave the gateway; default is REDACTED.
CREATE TABLE read_column_allowlist (
  id CHAR(36) PRIMARY KEY,
  source_id CHAR(36) NOT NULL,
  table_name VARCHAR(64) NOT NULL,
  column_name VARCHAR(64) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_source_table_column(source_id,table_name,column_name),
  CONSTRAINT fk_safe_columns_source FOREIGN KEY(source_id) REFERENCES data_sources(id),
  CONSTRAINT fk_safe_columns_actor FOREIGN KEY(created_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='明确允许显示的字段，默认全部脱敏';
