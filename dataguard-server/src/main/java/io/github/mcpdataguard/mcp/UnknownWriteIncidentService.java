package io.github.mcpdataguard.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Idempotent, tenant-scoped security incident for uncertain remote commits.
 * Runs within the caller's EXISTING platform transaction.
 * No SQL text, bind values, data-source secret or user PII is persisted here.
 */
final class UnknownWriteIncidentService {
 private UnknownWriteIncidentService(){}
 static void record(JdbcTemplate jdbc,UUID organization,UUID task,String cause){
  if(!"LEASE_EXPIRED".equals(cause) && !"WORKER_FAILURE".equals(cause))
   throw new IllegalArgumentException("Unsupported unknown-write incident reason");
  jdbc.update("""
    INSERT INTO dg_alert_rule(id,org_id,code,rule,enabled,severity)
    VALUES(?,?,'WRITE_EXECUTION_UNKNOWN',
      '{"kind":"UNCERTAIN_REMOTE_COMMIT"}'::jsonb,true,'CRITICAL')
    ON CONFLICT(org_id,code) DO NOTHING
    """,UUID.randomUUID(),organization);
  String fingerprint;
  try{
   fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest((organization+":"+task).getBytes(StandardCharsets.UTF_8)));
  }catch(java.security.NoSuchAlgorithmException ex){
   throw new IllegalStateException(ex);
  }
  jdbc.update("""
    INSERT INTO dg_alert_event(id,org_id,rule_id,fingerprint,event_state,details)
    SELECT ?,r.org_id,r.id,?,'OPEN',
       jsonb_build_object('taskId',?,'cause',CAST(? AS text))
    FROM dg_alert_rule r
    WHERE r.org_id=? AND r.code='WRITE_EXECUTION_UNKNOWN'
    ON CONFLICT(org_id,rule_id,fingerprint) WHERE event_state='OPEN'
    DO UPDATE SET observed_at=now()
    """,UUID.randomUUID(),fingerprint,task.toString(),cause,organization);
 }
}
