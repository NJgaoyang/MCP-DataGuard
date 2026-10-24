package io.github.mcpdataguard.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Produces one actionable OPEN alert for each tenant / datasource / denial reason.
 *
 * The fingerprint deliberately excludes SQL text and bind values. Repeated
 * denials refresh an existing incident instead of inserting alert floods.
 * The V4 partial unique index makes de-duplication safe under concurrency.
 *
 * Called inside the existing denied-request audit transaction so a missing
 * audit event cannot silently create a corresponding security incident.
 */
@Service
public final class DeniedSqlAlertService {
 private static final String RULE="SQL_CHECK_DENIED";
 private final JdbcTemplate jdbc;
 public DeniedSqlAlertService(JdbcTemplate jdbc){this.jdbc=jdbc;}

 private static String fingerprint(UUID orgId,UUID datasourceId,String reason){
  try{
   String safe=orgId+"|"+datasourceId+"|"+reason;
   byte[] digest=MessageDigest.getInstance("SHA-256")
     .digest(safe.getBytes(StandardCharsets.UTF_8));
   return HexFormat.of().formatHex(digest);
  }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }

 public void record(UUID orgId,UUID datasourceId,String reason){
  if(orgId==null||datasourceId==null||reason==null||
     !reason.matches("[A-Z0-9_]{1,100}"))
   throw new IllegalArgumentException("Invalid denial alert dimensions");

  jdbc.update("""
    INSERT INTO dg_alert_rule(id,org_id,code,rule,enabled,severity)
    VALUES(?,?,'SQL_CHECK_DENIED',
      jsonb_build_object('source','MCP_SQL_PREFLIGHT'),true,'MEDIUM')
    ON CONFLICT(org_id,code) DO NOTHING
    """,UUID.randomUUID(),orgId);

  jdbc.update("""
    INSERT INTO dg_alert_event(id,org_id,rule_id,fingerprint,event_state,details)
    SELECT ?,r.org_id,r.id,?,'OPEN',
      jsonb_build_object('reason',?,'datasourceId',?)
    FROM dg_alert_rule r
    WHERE r.org_id=? AND r.code='SQL_CHECK_DENIED' AND r.enabled=true
    ON CONFLICT(org_id,rule_id,fingerprint) WHERE event_state='OPEN'
    DO UPDATE SET observed_at=now()
    """,UUID.randomUUID(),fingerprint(orgId,datasourceId,reason),
       reason,datasourceId.toString(),orgId);
 }
}
