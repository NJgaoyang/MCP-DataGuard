-- Column grants are a second, explicit boundary in addition to owner-user allowlists.
-- No wildcard is supported; no grants means all column values remain redacted.
CREATE TABLE agent_column_permissions (
 id CHAR(36) PRIMARY KEY COMMENT '授权 ID',
 token_id CHAR(36) NOT NULL COMMENT 'Agent 令牌',
 source_id CHAR(36) NOT NULL COMMENT '数据源',
 table_name VARCHAR(64) NOT NULL COMMENT '表',
 column_name VARCHAR(64) NOT NULL COMMENT '允许查看的列',
 enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
 granted_by CHAR(36) NOT NULL COMMENT '操作管理员',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_agent_column(token_id,source_id,table_name,column_name),
 CONSTRAINT fk_agent_column_token FOREIGN KEY(token_id) REFERENCES app_agent_tokens(id),
 CONSTRAINT fk_agent_column_source FOREIGN KEY(source_id) REFERENCES data_sources(id),
 CONSTRAINT fk_agent_column_granter FOREIGN KEY(granted_by) REFERENCES app_users(id),
 INDEX idx_agent_columns(token_id,source_id,table_name,enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 额外列级披露授权，和用户字段授权取交集';
