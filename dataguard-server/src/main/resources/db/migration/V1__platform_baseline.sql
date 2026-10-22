-- PostgreSQL 17; one database `dataguard`, dedicated `platform` schema and dg_platform role.
-- New non-production baseline replacing MySQL V1; no existing production data is migrated.
-- OIDC digest fields must use canonical length-prefixed UTF-8 SHA-256 and original-value recheck.
-- Before Flyway, provision the `platform` schema owned by the dedicated platform role.
-- MCP DataGuard - PLATFORM BUSINESS SCHEMA - PostgreSQL 17 (V0.4 development baseline)
-- All timestamps timestamptz UTC, identifiers UUID created by the application.
-- No Flowable ACT_* tables here; Flowable uses the separate `flowable` SCHEMA in the SAME database.
-- Environment-specific credentials must never be stored in plaintext.
-- FK constraints verify object existence, application services MUST enforce org_id access on every query.

-- Fail closed: migration requires a pre-created, authorized platform schema.
SET search_path TO platform;

CREATE TABLE dg_organization (
 id UUID PRIMARY KEY, code VARCHAR(80) NOT NULL UNIQUE, name VARCHAR(200) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 settings JSONB NOT NULL DEFAULT '{}'::jsonb,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE dg_user (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id),
 oidc_issuer VARCHAR(512), oidc_subject VARCHAR(512), oidc_identity_sha256 CHAR(64), username VARCHAR(128) NOT NULL,
 display_name VARCHAR(200) NOT NULL, email VARCHAR(320), password_hash VARCHAR(255),
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','LOCKED','DISABLED')),
 last_login_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id), UNIQUE(org_id,username), UNIQUE(org_id,oidc_identity_sha256)
);
CREATE TABLE dg_role (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), code VARCHAR(100) NOT NULL,
 name VARCHAR(128) NOT NULL, permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
 system_role BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id), UNIQUE(org_id,code)
);
CREATE TABLE dg_user_role (
 org_id UUID NOT NULL REFERENCES dg_organization(id), user_id UUID NOT NULL, role_id UUID NOT NULL,
 assigned_by UUID, assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(org_id,user_id,role_id),
 FOREIGN KEY(org_id,user_id) REFERENCES dg_user(org_id,id), FOREIGN KEY(org_id,role_id) REFERENCES dg_role(org_id,id),
 FOREIGN KEY(org_id,assigned_by) REFERENCES dg_user(org_id,id)
);
CREATE TABLE dg_user_session (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, user_id UUID NOT NULL, token_digest CHAR(64) NOT NULL UNIQUE,
 expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(org_id,user_id) REFERENCES dg_user(org_id,id)
);
CREATE INDEX idx_dg_user_session_active ON dg_user_session(org_id,user_id,expires_at) WHERE revoked_at IS NULL;

CREATE TABLE dg_agent (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), owner_user_id UUID NOT NULL,
 code VARCHAR(120) NOT NULL, name VARCHAR(200) NOT NULL, description TEXT,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED','REVOKED')),
 rate_limit_per_minute INT NOT NULL DEFAULT 60 CHECK(rate_limit_per_minute BETWEEN 1 AND 100000),
 concurrency_limit INT NOT NULL DEFAULT 4 CHECK(concurrency_limit BETWEEN 1 AND 1000),
 max_rows INT NOT NULL DEFAULT 200 CHECK(max_rows BETWEEN 1 AND 100000),
 max_result_bytes BIGINT NOT NULL DEFAULT 1048576 CHECK(max_result_bytes > 0),
 max_execution_ms INT NOT NULL DEFAULT 10000 CHECK(max_execution_ms BETWEEN 100 AND 3600000),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id), UNIQUE(org_id,code), FOREIGN KEY(org_id,owner_user_id) REFERENCES dg_user(org_id,id)
);
CREATE TABLE dg_agent_identity (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, agent_id UUID NOT NULL,
 issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL, audience VARCHAR(255) NOT NULL,
 identity_sha256 CHAR(64) NOT NULL,
 client_id VARCHAR(255), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), revoked_at TIMESTAMPTZ,
 FOREIGN KEY(org_id,agent_id) REFERENCES dg_agent(org_id,id), UNIQUE(identity_sha256), UNIQUE(org_id,id)
);
CREATE INDEX idx_dg_agent_identity_lookup ON dg_agent_identity(identity_sha256,status);

CREATE TABLE dg_datasource (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), owner_user_id UUID NOT NULL,
 code VARCHAR(120) NOT NULL, name VARCHAR(255) NOT NULL,
 db_type VARCHAR(48) NOT NULL, environment VARCHAR(24) NOT NULL CHECK(environment IN ('DEV','TEST','STAGING','PROD')),
 host VARCHAR(512) NOT NULL, port INT NOT NULL CHECK(port BETWEEN 1 AND 65535),
 database_name VARCHAR(255), tls_mode VARCHAR(32) NOT NULL DEFAULT 'VERIFY_FULL',
 server_version VARCHAR(100), options JSONB NOT NULL DEFAULT '{}'::jsonb,
 status VARCHAR(16) NOT NULL DEFAULT 'DISABLED' CHECK(status IN ('ACTIVE','DISABLED','ERROR')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id), UNIQUE(org_id,code), FOREIGN KEY(org_id,owner_user_id) REFERENCES dg_user(org_id,id)
);
CREATE TABLE dg_datasource_secret (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, datasource_id UUID NOT NULL,
 purpose VARCHAR(16) NOT NULL CHECK(purpose IN ('READ','WRITE')),
 provider VARCHAR(48) NOT NULL CHECK(provider IN ('ENV','VAULT','KMS','K8S_SECRET')),
 secret_ref VARCHAR(1024) NOT NULL, rotated_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(org_id,datasource_id,purpose), FOREIGN KEY(org_id,datasource_id) REFERENCES dg_datasource(org_id,id)
);
CREATE TABLE dg_datasource_capability (
 org_id UUID NOT NULL, datasource_id UUID NOT NULL, capability VARCHAR(64) NOT NULL,
 support_status VARCHAR(16) NOT NULL CHECK(support_status IN ('CERTIFIED','EXPERIMENTAL','UNSUPPORTED')),
 plugin_version VARCHAR(64) NOT NULL, certified_server_version VARCHAR(100),
 evidence_ref VARCHAR(512), certified_at TIMESTAMPTZ,
 PRIMARY KEY(org_id,datasource_id,capability), FOREIGN KEY(org_id,datasource_id) REFERENCES dg_datasource(org_id,id)
);
CREATE TABLE dg_agent_permission (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, agent_id UUID NOT NULL, datasource_id UUID NOT NULL,
 tool_name VARCHAR(80) NOT NULL, object_pattern VARCHAR(512) NOT NULL,
 action VARCHAR(16) NOT NULL CHECK(action IN ('ALLOW','DENY')),
 granted_by UUID NOT NULL, expires_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(org_id,agent_id) REFERENCES dg_agent(org_id,id),
 FOREIGN KEY(org_id,datasource_id) REFERENCES dg_datasource(org_id,id),
 FOREIGN KEY(org_id,granted_by) REFERENCES dg_user(org_id,id),
 UNIQUE(org_id,agent_id,datasource_id,tool_name,object_pattern)
);
CREATE INDEX idx_dg_agent_permission_check ON dg_agent_permission(org_id,agent_id,datasource_id,tool_name);
CREATE TABLE dg_object_grant (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, agent_id UUID NOT NULL, datasource_id UUID NOT NULL,
 schema_pattern VARCHAR(255) NOT NULL, table_pattern VARCHAR(255) NOT NULL, column_pattern VARCHAR(255) NOT NULL DEFAULT '*',
 operations TEXT[] NOT NULL, decision VARCHAR(16) NOT NULL CHECK(decision IN ('ALLOW','DENY')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(org_id,agent_id) REFERENCES dg_agent(org_id,id),
 FOREIGN KEY(org_id,datasource_id) REFERENCES dg_datasource(org_id,id)
);
CREATE INDEX idx_dg_object_grant_lookup ON dg_object_grant(org_id,agent_id,datasource_id);

CREATE TABLE dg_sql_policy (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), code VARCHAR(120) NOT NULL,
 name VARCHAR(200) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','DISABLED')),
 latest_version INT NOT NULL DEFAULT 0 CHECK(latest_version >= 0),
 created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id), UNIQUE(org_id,code), FOREIGN KEY(org_id,created_by) REFERENCES dg_user(org_id,id)
);
CREATE TABLE dg_sql_policy_version (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, policy_id UUID NOT NULL, revision INT NOT NULL CHECK(revision > 0),
 priority INT NOT NULL DEFAULT 100, decision VARCHAR(24) NOT NULL CHECK(decision IN ('ALLOW','REQUIRE_APPROVAL','DENY')),
 rule JSONB NOT NULL, rule_digest CHAR(64) NOT NULL, approved_by UUID, published_by UUID,
 published_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(org_id,id), UNIQUE(org_id,policy_id,revision), FOREIGN KEY(org_id,policy_id) REFERENCES dg_sql_policy(org_id,id),
 FOREIGN KEY(org_id,approved_by) REFERENCES dg_user(org_id,id), FOREIGN KEY(org_id,published_by) REFERENCES dg_user(org_id,id)
);
CREATE INDEX idx_dg_policy_version_order ON dg_sql_policy_version(org_id,priority, published_at);

CREATE TABLE dg_workflow_template (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), code VARCHAR(100) NOT NULL,
 name VARCHAR(255) NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','DISABLED')),
 created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id), UNIQUE(org_id,code), FOREIGN KEY(org_id,created_by) REFERENCES dg_user(org_id,id)
);
CREATE TABLE dg_workflow_version (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, workflow_id UUID NOT NULL, revision INT NOT NULL CHECK(revision > 0),
 bpmn_xml TEXT NOT NULL, bpmn_digest CHAR(64) NOT NULL, flowable_definition_key VARCHAR(160) NOT NULL,
 flowable_definition_id VARCHAR(256),
 status VARCHAR(20) NOT NULL CHECK(status IN ('DRAFT','PUBLISHED','RETIRED')),
 published_by UUID, published_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(org_id,id), UNIQUE(org_id,workflow_id,revision), FOREIGN KEY(org_id,workflow_id) REFERENCES dg_workflow_template(org_id,id),
 FOREIGN KEY(org_id,published_by) REFERENCES dg_user(org_id,id)
);
CREATE TABLE dg_workflow_binding (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), datasource_id UUID,
 operation VARCHAR(32) NOT NULL, risk_level VARCHAR(16) NOT NULL CHECK(risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
 workflow_version_id UUID NOT NULL, priority INT NOT NULL DEFAULT 100,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(org_id,datasource_id) REFERENCES dg_datasource(org_id,id),
 FOREIGN KEY(org_id,workflow_version_id) REFERENCES dg_workflow_version(org_id,id)
);
CREATE INDEX idx_dg_workflow_binding_match ON dg_workflow_binding(org_id,datasource_id,operation,risk_level,priority);

CREATE TABLE dg_sql_request (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), agent_id UUID NOT NULL,
 requester_user_id UUID, datasource_id UUID NOT NULL, tool_name VARCHAR(80) NOT NULL,
 database_name VARCHAR(255), operation VARCHAR(48), sql_fingerprint CHAR(64),
 risk_level VARCHAR(16) CHECK(risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL','UNKNOWN')),
 decision VARCHAR(24) NOT NULL CHECK(decision IN ('ALLOW','REQUIRE_APPROVAL','DENY','ERROR')),
 policy_version_id UUID, request_status VARCHAR(24) NOT NULL CHECK(request_status IN ('RECEIVED','DENIED','READ_DONE','PENDING_APPROVAL','FAILED')),
 request_idempotency_key VARCHAR(160), trace_id VARCHAR(128),
 received_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id), FOREIGN KEY(org_id,agent_id) REFERENCES dg_agent(org_id,id),
 FOREIGN KEY(org_id,requester_user_id) REFERENCES dg_user(org_id,id),
 FOREIGN KEY(org_id,datasource_id) REFERENCES dg_datasource(org_id,id),
 FOREIGN KEY(org_id,policy_version_id) REFERENCES dg_sql_policy_version(org_id,id)
);
CREATE UNIQUE INDEX uq_dg_sql_request_idempotent ON dg_sql_request(org_id,agent_id,request_idempotency_key) WHERE request_idempotency_key IS NOT NULL;
CREATE INDEX idx_dg_sql_request_search ON dg_sql_request(org_id,received_at DESC,agent_id,datasource_id);
CREATE TABLE dg_sql_request_payload (
 org_id UUID NOT NULL, request_id UUID NOT NULL,
 encrypted_sql BYTEA NOT NULL, encrypted_parameters BYTEA NOT NULL,
 encryption_key_ref VARCHAR(512) NOT NULL, encryption_algorithm VARCHAR(48) NOT NULL,
 canonical_payload_hmac CHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(org_id,request_id), FOREIGN KEY(org_id,request_id) REFERENCES dg_sql_request(org_id,id)
);
CREATE TABLE dg_sql_read_summary (
 org_id UUID NOT NULL, request_id UUID NOT NULL, returned_rows BIGINT NOT NULL DEFAULT 0 CHECK(returned_rows >= 0),
 result_bytes BIGINT NOT NULL DEFAULT 0 CHECK(result_bytes >= 0), truncated BOOLEAN NOT NULL DEFAULT FALSE,
 duration_ms BIGINT NOT NULL DEFAULT 0, sanitized_error_code VARCHAR(80),
 PRIMARY KEY(org_id,request_id), FOREIGN KEY(org_id,request_id) REFERENCES dg_sql_request(org_id,id)
);
CREATE TABLE dg_sql_approval (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, request_id UUID NOT NULL,
 workflow_version_id UUID NOT NULL, process_instance_id VARCHAR(256) UNIQUE,
 approval_status VARCHAR(24) NOT NULL CHECK(approval_status IN ('PENDING','APPROVED','REJECTED','CANCELLED','EXPIRED')),
 execution_status VARCHAR(24) NOT NULL CHECK(execution_status IN ('NOT_SCHEDULED','QUEUED','CLAIMED','EXECUTING','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
 expires_at TIMESTAMPTZ NOT NULL, decided_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id), UNIQUE(org_id,request_id),
 FOREIGN KEY(org_id,request_id) REFERENCES dg_sql_request(org_id,id),
 FOREIGN KEY(org_id,workflow_version_id) REFERENCES dg_workflow_version(org_id,id)
);
CREATE INDEX idx_dg_approval_inbox ON dg_sql_approval(org_id,approval_status,created_at DESC);
CREATE TABLE dg_sql_approval_action (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, approval_id UUID NOT NULL, reviewer_user_id UUID NOT NULL,
 flowable_task_id VARCHAR(256) NOT NULL, action VARCHAR(20) NOT NULL CHECK(action IN ('APPROVE','REJECT','DELEGATE','RETURN','COMMENT','CANCEL')),
 comment_text TEXT, acted_at TIMESTAMPTZ NOT NULL DEFAULT now(), idempotency_key VARCHAR(160),
 FOREIGN KEY(org_id,approval_id) REFERENCES dg_sql_approval(org_id,id),
 FOREIGN KEY(org_id,reviewer_user_id) REFERENCES dg_user(org_id,id)
);
CREATE UNIQUE INDEX uq_dg_approval_action_idempotent ON dg_sql_approval_action(org_id,approval_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_dg_approval_action_history ON dg_sql_approval_action(org_id,approval_id,acted_at);
CREATE TABLE dg_approval_attachment (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, approval_id UUID NOT NULL,
 uploaded_by UUID NOT NULL, storage_key VARCHAR(512) NOT NULL, file_name VARCHAR(255) NOT NULL,
 media_type VARCHAR(160) NOT NULL, byte_size BIGINT NOT NULL CHECK(byte_size > 0), sha256 CHAR(64) NOT NULL,
 scan_state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(scan_state IN ('PENDING','CLEAN','QUARANTINED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(org_id,approval_id) REFERENCES dg_sql_approval(org_id,id), FOREIGN KEY(org_id,uploaded_by) REFERENCES dg_user(org_id,id)
);

CREATE TABLE dg_execution_task (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, approval_id UUID NOT NULL,
 task_state VARCHAR(24) NOT NULL CHECK(task_state IN ('QUEUED','CLAIMED','EXECUTING','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
 payload_hmac CHAR(64) NOT NULL, authorization_digest CHAR(64) NOT NULL,
 authorization_expires_at TIMESTAMPTZ NOT NULL,
 lease_owner VARCHAR(128), lease_until TIMESTAMPTZ,
 fencing_token BIGINT NOT NULL DEFAULT 0 CHECK(fencing_token >= 0), attempt_no INT NOT NULL DEFAULT 0 CHECK(attempt_no >= 0),
 enqueued_at TIMESTAMPTZ NOT NULL DEFAULT now(), started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id), UNIQUE(org_id,approval_id), FOREIGN KEY(org_id,approval_id) REFERENCES dg_sql_approval(org_id,id)
);
CREATE INDEX idx_dg_execution_task_pickup ON dg_execution_task(task_state,lease_until,enqueued_at) WHERE task_state IN ('QUEUED','CLAIMED');
CREATE TABLE dg_execution_attempt (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, task_id UUID NOT NULL, attempt_no INT NOT NULL CHECK(attempt_no > 0),
 phase VARCHAR(24) NOT NULL CHECK(phase IN ('CLAIMED','BEFORE_SEND','IN_FLIGHT','ACKNOWLEDGED','FAILED','UNKNOWN')),
 started_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
 affected_rows BIGINT, db_transaction_id VARCHAR(256), error_code VARCHAR(80), error_category VARCHAR(64),
 details JSONB NOT NULL DEFAULT '{}'::jsonb,
 UNIQUE(org_id,task_id,attempt_no), FOREIGN KEY(org_id,task_id) REFERENCES dg_execution_task(org_id,id)
);

CREATE TABLE dg_audit_stream (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), stream_key VARCHAR(128) NOT NULL,
 last_seq BIGINT NOT NULL DEFAULT 0 CHECK(last_seq >= 0), last_hash CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(org_id,id), UNIQUE(org_id,stream_key)
);
CREATE TABLE dg_audit_event (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, stream_id UUID NOT NULL, seq_no BIGINT NOT NULL CHECK(seq_no > 0),
 request_id UUID, actor_type VARCHAR(24) NOT NULL, actor_id VARCHAR(160), event_type VARCHAR(120) NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(), event_data JSONB NOT NULL,
 prev_hash CHAR(64) NOT NULL, event_hash CHAR(64) NOT NULL,
 UNIQUE(org_id,stream_id,seq_no), FOREIGN KEY(org_id,stream_id) REFERENCES dg_audit_stream(org_id,id),
 FOREIGN KEY(org_id,request_id) REFERENCES dg_sql_request(org_id,id)
);
CREATE INDEX idx_dg_audit_event_search ON dg_audit_event(org_id,occurred_at DESC,event_type);
CREATE TABLE dg_audit_anchor (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, stream_id UUID NOT NULL, up_to_seq BIGINT NOT NULL,
 anchor_hash CHAR(64) NOT NULL, archive_ref VARCHAR(1024) NOT NULL,
 signed_at TIMESTAMPTZ NOT NULL DEFAULT now(), signer_key_ref VARCHAR(512) NOT NULL,
 UNIQUE(org_id,stream_id,up_to_seq), FOREIGN KEY(org_id,stream_id) REFERENCES dg_audit_stream(org_id,id)
);
CREATE TABLE dg_outbox_event (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), aggregate_type VARCHAR(64) NOT NULL,
 aggregate_id UUID NOT NULL, event_type VARCHAR(128) NOT NULL, payload JSONB NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','PUBLISHED','FAILED')),
 available_at TIMESTAMPTZ NOT NULL DEFAULT now(), dispatched_at TIMESTAMPTZ,
 attempts INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(org_id,id)
);
CREATE INDEX idx_dg_outbox_pending ON dg_outbox_event(state,available_at) WHERE state IN ('PENDING','FAILED');

CREATE TABLE dg_webhook_endpoint (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), url VARCHAR(2048) NOT NULL,
 signing_secret_ref VARCHAR(1024) NOT NULL, subscribed_events TEXT[] NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(org_id,id)
);
CREATE TABLE dg_webhook_delivery (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, endpoint_id UUID NOT NULL, event_id UUID NOT NULL,
 delivery_status VARCHAR(16) NOT NULL CHECK(delivery_status IN ('PENDING','DELIVERED','RETRY','DEAD')),
 response_status INT, retries INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ,
 delivered_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(org_id,endpoint_id,event_id), FOREIGN KEY(org_id,endpoint_id) REFERENCES dg_webhook_endpoint(org_id,id),
 FOREIGN KEY(org_id,event_id) REFERENCES dg_outbox_event(org_id,id)
);
CREATE INDEX idx_dg_webhook_retry ON dg_webhook_delivery(delivery_status,next_attempt_at);
CREATE TABLE dg_notification (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), user_id UUID NOT NULL,
 channel VARCHAR(24) NOT NULL CHECK(channel IN ('IN_APP','EMAIL','WEBHOOK')),
 template_code VARCHAR(100) NOT NULL, payload JSONB NOT NULL, read_at TIMESTAMPTZ,
 sent_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(org_id,user_id) REFERENCES dg_user(org_id,id)
);
CREATE INDEX idx_dg_notification_unread ON dg_notification(org_id,user_id,created_at DESC) WHERE read_at IS NULL;
CREATE TABLE dg_alert_rule (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), code VARCHAR(120) NOT NULL,
 rule JSONB NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, severity VARCHAR(16) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(org_id,id), UNIQUE(org_id,code)
);
CREATE TABLE dg_alert_event (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, rule_id UUID NOT NULL, fingerprint CHAR(64) NOT NULL,
 event_state VARCHAR(16) NOT NULL CHECK(event_state IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
 observed_at TIMESTAMPTZ NOT NULL DEFAULT now(), resolved_at TIMESTAMPTZ,
 details JSONB NOT NULL, FOREIGN KEY(org_id,rule_id) REFERENCES dg_alert_rule(org_id,id)
);
CREATE INDEX idx_dg_alert_open ON dg_alert_event(org_id,event_state,observed_at DESC);
CREATE TABLE dg_plugin_registry (
 id UUID PRIMARY KEY, plugin_key VARCHAR(128) NOT NULL, plugin_version VARCHAR(64) NOT NULL,
 checksum_sha256 CHAR(64) NOT NULL, manifest JSONB NOT NULL, certification JSONB NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('REGISTERED','CERTIFIED','DISABLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(plugin_key,plugin_version)
);
CREATE TABLE dg_system_event (
 id UUID PRIMARY KEY, org_id UUID REFERENCES dg_organization(id), actor VARCHAR(255) NOT NULL,
 operation VARCHAR(120) NOT NULL, resource_type VARCHAR(100), resource_id VARCHAR(160),
 detail JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dg_system_event ON dg_system_event(org_id,created_at DESC);
CREATE TABLE dg_bootstrap_state (
 singleton_key SMALLINT PRIMARY KEY DEFAULT 1 CHECK(singleton_key=1), initialized BOOLEAN NOT NULL DEFAULT FALSE,
 initialized_at TIMESTAMPTZ, initialized_by UUID, bootstrap_completed_version VARCHAR(64)
);
CREATE TABLE dg_migration_record (
 id UUID PRIMARY KEY, component VARCHAR(128) NOT NULL, from_version VARCHAR(64), to_version VARCHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL CHECK(state IN ('STARTED','SUCCEEDED','FAILED','ROLLED_BACK')),
 started_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ, details JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- Further V1 product capabilities: safe masking, quota accounting, schema cache,
-- human task delegation, backup jobs and security incident handling.
CREATE TABLE dg_mask_rule (
 id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES dg_organization(id), datasource_id UUID NOT NULL,
 schema_pattern VARCHAR(255) NOT NULL, table_pattern VARCHAR(255) NOT NULL, column_pattern VARCHAR(255) NOT NULL,
 algorithm VARCHAR(32) NOT NULL CHECK(algorithm IN ('REDACT','PARTIAL','HASH','NULLIFY')),
 rule_config JSONB NOT NULL DEFAULT '{}'::jsonb, priority INT NOT NULL DEFAULT 100,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(org_id,datasource_id) REFERENCES dg_datasource(org_id,id)
);
CREATE INDEX idx_dg_mask_rule ON dg_mask_rule(org_id,datasource_id,priority);
CREATE TABLE dg_agent_usage_daily (
 org_id UUID NOT NULL, agent_id UUID NOT NULL, usage_day DATE NOT NULL,
 request_count BIGINT NOT NULL DEFAULT 0 CHECK(request_count>=0),
 denied_count BIGINT NOT NULL DEFAULT 0 CHECK(denied_count>=0),
 approval_count BIGINT NOT NULL DEFAULT 0 CHECK(approval_count>=0),
 returned_bytes BIGINT NOT NULL DEFAULT 0 CHECK(returned_bytes>=0),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(org_id,agent_id,usage_day),
 FOREIGN KEY(org_id,agent_id) REFERENCES dg_agent(org_id,id)
);
CREATE TABLE dg_schema_cache (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, datasource_id UUID NOT NULL,
 schema_name VARCHAR(255) NOT NULL, object_name VARCHAR(255) NOT NULL,
 permission_scope_hash CHAR(64) NOT NULL, cache_payload JSONB NOT NULL,
 cached_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL,
 UNIQUE(org_id,datasource_id,schema_name,object_name,permission_scope_hash),
 FOREIGN KEY(org_id,datasource_id) REFERENCES dg_datasource(org_id,id)
);
CREATE INDEX idx_dg_schema_cache_ttl ON dg_schema_cache(expires_at);
CREATE TABLE dg_approval_delegation (
 id UUID PRIMARY KEY, org_id UUID NOT NULL, approval_id UUID NOT NULL,
 from_user_id UUID NOT NULL, to_user_id UUID NOT NULL,
 reason TEXT, expires_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(org_id,approval_id) REFERENCES dg_sql_approval(org_id,id),
 FOREIGN KEY(org_id,from_user_id) REFERENCES dg_user(org_id,id),
 FOREIGN KEY(org_id,to_user_id) REFERENCES dg_user(org_id,id),
 CHECK(from_user_id<>to_user_id)
);
CREATE TABLE dg_backup_job (
 id UUID PRIMARY KEY, org_id UUID REFERENCES dg_organization(id),
 kind VARCHAR(32) NOT NULL CHECK(kind IN ('DATABASE','PLATFORM_SCHEMA','FLOWABLE_SCHEMA','AUDIT_ARCHIVE','CONFIG')),
 backup_state VARCHAR(24) NOT NULL CHECK(backup_state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','VERIFIED')),
 artifact_ref VARCHAR(1024), checksum_sha256 CHAR(64),
 started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, verified_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE dg_security_incident (
 id UUID PRIMARY KEY, org_id UUID REFERENCES dg_organization(id),
 severity VARCHAR(16) NOT NULL CHECK(severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
 state VARCHAR(24) NOT NULL CHECK(state IN ('OPEN','INVESTIGATING','CONTAINED','CLOSED')),
 incident_type VARCHAR(128) NOT NULL, evidence_ref VARCHAR(1024), summary TEXT NOT NULL,
 acknowledged_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), resolved_at TIMESTAMPTZ,
 FOREIGN KEY(org_id,acknowledged_by) REFERENCES dg_user(org_id,id)
);
CREATE INDEX idx_dg_security_incident_open ON dg_security_incident(org_id,state,created_at DESC);