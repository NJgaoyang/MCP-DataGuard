package io.github.mcpdataguard.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization is generated server side, never accepted as a caller-supplied
 * privilege. A unique (org,approval) constraint prevents duplicate enqueue.
 * No JDBC target-write connection is opened by this service.
 */
@Service
public class ApprovedExecutionQueue {
 private final JdbcTemplate jdbc;
 private final ExecutionEligibilityService eligibility;
 private final boolean enabled;
 private final ExecutionTicketSigner signer;
 public ApprovedExecutionQueue(JdbcTemplate jdbc,ExecutionEligibilityService eligibility,
     ExecutionTicketSigner signer,@Value("${dataguard.write.queue.enabled:false}") boolean enabled){
  this.jdbc=jdbc;this.eligibility=eligibility;this.signer=signer;this.enabled=enabled;
 }
 public record QueueResult(boolean queued,String code,UUID taskId){}
 @Transactional
 public QueueResult enqueue(UUID organization,UUID approval,String fingerprint,
                            String payloadHmac){
  if(!enabled)return new QueueResult(false,"WRITE_QUEUE_DISABLED",null);
  if(!signer.configured())return new QueueResult(false,"EXECUTION_SIGNING_UNAVAILABLE",null);
  if(organization==null||approval==null)return new QueueResult(false,"INVALID_APPROVAL",null);
  // Serialize competing submissions with the same approval; any competing
  // state change will be rechecked after acquiring this lock.
  var rows=jdbc.query("""
   SELECT id FROM dg_sql_approval WHERE org_id=? AND id=?
     AND approval_status='APPROVED' AND execution_status='NOT_SCHEDULED'
     AND expires_at>now() FOR UPDATE
   """,(rs,i)->rs.getObject(1,UUID.class),organization,approval);
  if(rows.size()!=1)return new QueueResult(false,"APPROVAL_UNAVAILABLE",null);
  var verified=eligibility.inspect(organization,approval,fingerprint,payloadHmac);
  if(!verified.eligible())return new QueueResult(false,verified.reason(),null);
  // The authorization digest commits to tenant, approval, immutable SQL
  // fingerprint, encrypted payload HMAC and a random one-time nonce.
  UUID task=UUID.randomUUID();
  String digest=signer.sign(organization,approval,verified.requestId(),
    fingerprint,payloadHmac,task);
  int updated=jdbc.update("""
   UPDATE dg_sql_approval SET execution_status='QUEUED',
      updated_at=now(),version=version+1
   WHERE org_id=? AND id=? AND approval_status='APPROVED'
     AND execution_status='NOT_SCHEDULED' AND expires_at>now()
   """,organization,approval);
  if(updated!=1)throw new IllegalStateException("Concurrent execution claim");
  jdbc.update("""
   INSERT INTO dg_execution_task
      (id,org_id,approval_id,task_state,payload_hmac,authorization_digest,
       authorization_expires_at)
   SELECT ?,?,?, 'QUEUED',?,?,LEAST(expires_at,now()+interval '5 minutes')
   FROM dg_sql_approval WHERE org_id=? AND id=?
   """,task,organization,approval,payloadHmac,digest,organization,approval);
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,'EXECUTION_QUEUE','WRITE_TASK_QUEUED','EXECUTION_TASK',?)
   """,UUID.randomUUID(),organization,task.toString());
  return new QueueResult(true,"QUEUED_NO_PHYSICAL_EXECUTION",task);
 }
}
