package io.github.mcpdataguard.mcp;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fail-closed recovery: an unacknowledged write is never retried automatically.
 * If a worker crashes after sending DML, the outcome can be uncertain. Persist
 * UNKNOWN for manual reconciliation rather than risking a duplicate mutation.
 */
@Component
public class ExecutionRecoveryWatchdog {
 private final JdbcTemplate jdbc;
 private final boolean enabled;
 public ExecutionRecoveryWatchdog(JdbcTemplate jdbc,
    @Value("${dataguard.write.recovery.enabled:false}") boolean enabled){
  this.jdbc=jdbc;this.enabled=enabled;
 }
 @Scheduled(fixedDelayString="${dataguard.write.recovery.poll-ms:30000}")
 @Transactional
 public void quarantineExpiredLeases(){
  if(!enabled)return;
  var expired=jdbc.query("""
   SELECT id,org_id,approval_id,task_state
   FROM dg_execution_task WHERE task_state IN ('CLAIMED','EXECUTING')
      AND lease_until IS NOT NULL AND lease_until<now()
   ORDER BY lease_until,id LIMIT 50 FOR UPDATE SKIP LOCKED
   """,(rs,i)->Map.<String,Object>of(
     "id",rs.getObject(1,UUID.class),"org",rs.getObject(2,UUID.class),
     "approval",rs.getObject(3,UUID.class),"state",rs.getString(4)));
  for(var row:expired){
   UUID id=(UUID)row.get("id"),org=(UUID)row.get("org"),
     approval=(UUID)row.get("approval");
   jdbc.update("""
     UPDATE dg_execution_task SET task_state='UNKNOWN',lease_owner=NULL,
      lease_until=NULL,updated_at=now(),version=version+1
     WHERE org_id=? AND id=? AND task_state IN ('CLAIMED','EXECUTING')
     """,org,id);
   jdbc.update("""
     UPDATE dg_sql_approval SET execution_status='UNKNOWN',
      updated_at=now(),version=version+1
     WHERE org_id=? AND id=? AND execution_status IN ('CLAIMED','EXECUTING')
     """,org,approval);
   jdbc.update("""
     UPDATE dg_execution_attempt
     SET phase='UNKNOWN',completed_at=now(),
       error_code='WRITE_OUTCOME_UNCERTAIN',
       error_category='REQUIRES_RECONCILIATION'
     WHERE org_id=? AND task_id=?
       AND phase IN ('CLAIMED','BEFORE_SEND','IN_FLIGHT')
     """,org,id);
   jdbc.update("""
     INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
     VALUES (?,?,'EXECUTION_WATCHDOG','WRITE_RESULT_UNKNOWN','EXECUTION_TASK',?)
     """,UUID.randomUUID(),org,id.toString());
   UnknownWriteIncidentService.record(jdbc,org,id,"LEASE_EXPIRED");
  }
 }
}
