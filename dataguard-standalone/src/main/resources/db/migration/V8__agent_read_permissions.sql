-- Agent token permissions are distinct from owner-user approvals.
-- Fail closed: no grant means no access; owner approval and table/column allowlists remain mandatory.
CREATE TABLE agent_read_permissions (
  id CHAR(36) PRIMARY KEY COMMENT '授权记录 UUID',
  token_id CHAR(36) NOT NULL COMMENT 'Agent 访问令牌 ID',
  source_id CHAR(36) NOT NULL COMMENT '可访问数据源 ID',
  table_name VARCHAR(64) NOT NULL COMMENT '明确授权的表（不允许 *）',
  enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用授权',
  granted_by CHAR(36) NOT NULL COMMENT '授予权限的内部管理员',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_agent_source_table(token_id, source_id, table_name),
  INDEX idx_agent_grants_source(source_id, table_name, enabled),
  CONSTRAINT fk_agent_permission_token FOREIGN KEY(token_id) REFERENCES app_agent_tokens(id),
  CONSTRAINT fk_agent_permission_source FOREIGN KEY(source_id) REFERENCES data_sources(id),
  CONSTRAINT fk_agent_permission_granter FOREIGN KEY(granted_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 显式读取表授权；与所属用户审批权限取交集';
