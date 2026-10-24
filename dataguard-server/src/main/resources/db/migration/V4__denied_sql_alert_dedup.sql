-- V4. Deduplicate actionable OPEN security alerts per organization/rule/fingerprint.
-- Existing duplicate OPEN rows, if any, are retained as RESOLVED history, not deleted.
-- This migration does not enable SQL read/write execution.
WITH ranked AS (
  SELECT id, ROW_NUMBER() OVER(
    PARTITION BY org_id,rule_id,fingerprint
    ORDER BY observed_at DESC,id DESC
  ) AS row_no
  FROM dg_alert_event
  WHERE event_state='OPEN'
)
UPDATE dg_alert_event e
SET event_state='RESOLVED',resolved_at=COALESCE(e.resolved_at,now())
FROM ranked r
WHERE e.id=r.id AND r.row_no>1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_dg_alert_open_incident
  ON dg_alert_event(org_id,rule_id,fingerprint)
  WHERE event_state='OPEN';

COMMENT ON INDEX uq_dg_alert_open_incident IS
 'One actionable OPEN incident per organization, alert rule and safe fingerprint; ACKNOWLEDGED/RESOLVED history is preserved.';
