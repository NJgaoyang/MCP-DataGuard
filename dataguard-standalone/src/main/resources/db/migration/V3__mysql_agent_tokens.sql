CREATE TABLE app_agent_tokens (
 id CHAR(36) PRIMARY KEY,
 owner_user_id CHAR(36) NOT NULL COMMENT '系统内令牌所属用户',
 label VARCHAR(100) NOT NULL,
 token_digest CHAR(64) NOT NULL UNIQUE COMMENT 'SHA-256 only; no plaintext tokens',
 expires_at DATETIME(3) NOT NULL,
 revoked_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_agent_owner FOREIGN KEY(owner_user_id) REFERENCES app_users(id),
 INDEX idx_agent_owner(owner_user_id,revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MySQL 单体 MCP Agent 凭据';
