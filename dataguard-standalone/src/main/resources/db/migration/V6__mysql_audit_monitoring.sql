ALTER TABLE audit_logs ADD COLUMN duration_ms BIGINT NULL AFTER outcome;

CREATE TABLE security_alerts (
  id CHAR(36) PRIMARY KEY,
  event_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  actor_username VARCHAR(64) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  resource_id VARCHAR(160) NOT NULL,
  detail VARCHAR(500) NULL,
  acknowledged_by VARCHAR(64) NULL,
  acknowledged_at DATETIME(3) NULL,
  resolved_by VARCHAR(64) NULL,
  resolved_at DATETIME(3) NULL,
  INDEX idx_alert_status_time(status,event_time),
  INDEX idx_alert_severity_time(severity,event_time),
  CONSTRAINT chk_security_alert_severity CHECK(severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  CONSTRAINT chk_security_alert_status CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
