ALTER TABLE dg_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE dg_user ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE dg_user ADD COLUMN login_locked_until TIMESTAMPTZ;
CREATE TABLE dg_native_session (
 id UUID PRIMARY KEY,
 org_id UUID NOT NULL,
 user_id UUID NOT NULL,
 token_digest CHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ,
 FOREIGN KEY (org_id,user_id) REFERENCES dg_user(org_id,id)
);
CREATE INDEX ix_native_session_user ON dg_native_session(org_id,user_id,expires_at);
CREATE TABLE dg_native_agent_token (
 id UUID PRIMARY KEY,
 org_id UUID NOT NULL,
 agent_id UUID NOT NULL,
 token_digest CHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ,
 FOREIGN KEY (org_id,agent_id) REFERENCES dg_agent(org_id,id)
);
CREATE INDEX ix_native_agent_token_agent ON dg_native_agent_token(org_id,agent_id,expires_at);
