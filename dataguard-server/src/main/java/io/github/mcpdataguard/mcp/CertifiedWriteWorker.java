package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.workflow.contract.SafeApprovalGraph;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disabled-by-default physical write worker.
 * Each claim is durably IN_FLIGHT before touching the target database.
 * Any unexpected failure is UNKNOWN and NEVER automatically retried.
 */
@Component
public class CertifiedWriteWorker {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate transactions;
 private final ExecutionTicketSigner signer;
 private final ApprovalPayloadCrypto crypto;
 private final CertifiedWriteJdbcExecutor executor;
 private final boolean enabled;
 private record Task(UUID task,UUID org,UUID approval,UUID request,
    String fingerprint,String hmac,String ticket,String operation,
    String host,int port,String database,UUID datasource,
    byte[] encryptedSql,byte[] encryptedParams,
    String bpmn,String bpmnDigest,String risk){}
 public CertifiedWriteWorker(JdbcTemplate jdbc,TransactionTemplate transactions,
     ExecutionTicketSigner signer,ApprovalPayloadCrypto crypto,
     CertifiedWriteJdbcExecutor executor,
     @Value("${dataguard.write.execution.enabled:false}") boolean enabled){
  this.jdbc=jdbc;this.transactions=transactions;this.signer=signer;this.crypto=crypto;
  this.executor=executor;this.enabled=enabled;
 }
 @Scheduled(fixedDelayString="${dataguard.write.execution.poll-ms:15000}")
 public void runOne(){
  if(!enabled || !signer.configured() || !crypto.configured())return;
  Task task=transactions.execute(status->claim());
  if(task==null)return;
  // Everything after the IN_FLIGHT commit may be ambiguous on failure.
  // Never requeue or retry this task automatically.
  try{
   if(!signer.verify(task.org(),task.approval(),task.request(),
       task.fingerprint(),task.hmac(),task.task(),task.ticket()))
    throw new SecurityException("WRITE_TICKET_TAMPERED");
   var payload=crypto.open(task.encryptedSql(),task.encryptedParams(),task.hmac());
   String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
     .digest(payload.sql().getBytes(StandardCharsets.UTF_8)));
   if(!MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),
        task.fingerprint().getBytes(StandardCharsets.US_ASCII)))
    throw new SecurityException("WRITE_FINGERPRINT_MISMATCH");
   String normalized=payload.sql().strip().toUpperCase(java.util.Locale.ROOT);
   if(!normalized.startsWith(task.operation()+" "))
    throw new SecurityException("WRITE_OPERATION_MISMATCH");
   // Dedicated per-datasource write role, never the platform connection user.
   String prefix="DG_TARGET_"+task.datasource().toString().replace("-","").toUpperCase();
   String user=System.getenv(prefix+"_WRITE_USER");
   String password=System.getenv(prefix+"_WRITE_PASSWORD");
   if(user==null||user.isBlank()||password==null||password.isBlank())
    throw new SecurityException("WRITE_CREDENTIALS_UNAVAILABLE");
   var target=new CertifiedWriteJdbcExecutor.Target(task.host(),task.port(),
      task.database(),user,password);
   var result=executor.execute(target,payload.sql(),payload.parameters());
   transactions.executeWithoutResult(status->{
    if(jdbc.update("""
      UPDATE dg_execution_task SET task_state='SUCCEEDED',finished_at=now(),
        lease_until=NULL,updated_at=now(),version=version+1
      WHERE org_id=? AND id=? AND task_state='EXECUTING'
      """,task.org(),task.task())!=1)
     throw new IllegalStateException("Expired execution lease or duplicate completion");
    if(jdbc.update("""
      UPDATE dg_sql_approval SET execution_status='SUCCEEDED',
        updated_at=now(),version=version+1
      WHERE org_id=? AND id=? AND execution_status='EXECUTING'
      """,task.org(),task.approval())!=1)
     throw new IllegalStateException("Approval execution state changed");
    if(jdbc.update("""
      UPDATE dg_execution_attempt SET phase='ACKNOWLEDGED',
        completed_at=now(),affected_rows=?
      WHERE org_id=? AND task_id=? AND phase='IN_FLIGHT'
      """,result.affectedRows(),task.org(),task.task())!=1)
     throw new IllegalStateException("Missing matching execution attempt");
    event(task,"WRITE_EXECUTION_SUCCEEDED");
   });
  }catch(Exception e){
   // Even an apparent SQL error may have followed a remote commit.
   // Do not retry under another fencing token without manual reconciliation.
   try{transactions.executeWithoutResult(status->{
    jdbc.update("""
      UPDATE dg_execution_task SET task_state='UNKNOWN',finished_at=now(),
        lease_owner=NULL,lease_until=NULL,updated_at=now(),version=version+1
      WHERE org_id=? AND id=? AND task_state='EXECUTING'
      """,task.org(),task.task());
    jdbc.update("""
      UPDATE dg_sql_approval SET execution_status='UNKNOWN',
        updated_at=now(),version=version+1
      WHERE org_id=? AND id=? AND execution_status='EXECUTING'
      """,task.org(),task.approval());
    jdbc.update("""
      UPDATE dg_execution_attempt SET phase='UNKNOWN',completed_at=now(),
        error_code='WRITE_OUTCOME_UNCERTAIN',error_category='REQUIRES_RECONCILIATION'
      WHERE org_id=? AND task_id=? AND phase='IN_FLIGHT'
      """,task.org(),task.task());
    event(task,"WRITE_RESULT_UNKNOWN");
   });}catch(Exception ignored){
    System.err.println("Execution reconciliation marker failed (redacted)");
   }
  }
 }
 private Task claim(){
  List<Task> rows=jdbc.query("""
    SELECT t.id,t.org_id,t.approval_id,r.id,r.sql_fingerprint,
       p.canonical_payload_hmac,t.authorization_digest,r.operation,
       d.host,d.port,d.database_name,d.id,p.encrypted_sql,p.encrypted_parameters,
       v.bpmn_xml,v.bpmn_digest,r.risk_level
    FROM dg_execution_task t
    JOIN dg_sql_approval a ON a.org_id=t.org_id AND a.id=t.approval_id
    JOIN dg_sql_request r ON r.org_id=a.org_id AND r.id=a.request_id
    JOIN dg_workflow_version v ON v.org_id=a.org_id AND v.id=a.workflow_version_id
    JOIN dg_sql_request_payload p ON p.org_id=r.org_id AND p.request_id=r.id
    JOIN dg_datasource d ON d.org_id=r.org_id AND d.id=r.datasource_id
    JOIN dg_datasource_capability cap ON cap.org_id=d.org_id
      AND cap.datasource_id=d.id AND cap.capability='WRITE_EXECUTION'
      AND cap.support_status='CERTIFIED' AND cap.certified_at IS NOT NULL AND cap.evidence_ref IS NOT NULL
    JOIN dg_datasource_secret secret ON secret.org_id=d.org_id
      AND secret.datasource_id=d.id AND secret.purpose='WRITE'
      AND secret.provider='ENV'
      AND secret.secret_ref=('DG_TARGET_' || upper(replace(d.id::text,'-','')) || '_WRITE')
    WHERE t.task_state='QUEUED' AND t.authorization_expires_at>now()
      AND a.approval_status='APPROVED' AND a.execution_status='QUEUED'
      AND a.expires_at>now() AND d.status='ACTIVE' AND d.db_type='POSTGRESQL'
      AND NOT EXISTS(
        SELECT 1 FROM dg_outbox_event e
        WHERE e.org_id=a.org_id AND e.aggregate_id=a.id
          AND e.event_type IN ('APPROVAL_REQUESTED','APPROVAL_STEP_APPROVED')
          AND e.state<>'PUBLISHED'
      )
      AND (SELECT count(*) FROM dg_sql_approval_action ac
           WHERE ac.org_id=a.org_id AND ac.approval_id=a.id AND ac.action='APPROVE'
             AND ac.flowable_task_id NOT LIKE 'platform:%')=(CASE WHEN v.flowable_definition_key='safeWorkflowV2'
        THEN regexp_count(v.bpmn_xml,'<userTask') -
          (CASE WHEN v.bpmn_xml LIKE '%exclusiveGateway%'
                 AND r.risk_level NOT IN ('HIGH','CRITICAL') THEN 1 ELSE 0 END)
        ELSE 2 END)
      AND (SELECT count(DISTINCT ac.reviewer_user_id) FROM dg_sql_approval_action ac
           WHERE ac.org_id=a.org_id AND ac.approval_id=a.id AND ac.action='APPROVE')=(CASE WHEN v.flowable_definition_key='safeWorkflowV2'
        THEN regexp_count(v.bpmn_xml,'<userTask') -
          (CASE WHEN v.bpmn_xml LIKE '%exclusiveGateway%'
                 AND r.risk_level NOT IN ('HIGH','CRITICAL') THEN 1 ELSE 0 END)
        ELSE 2 END)
      AND (SELECT count(*) FROM dg_outbox_event e WHERE e.org_id=a.org_id
           AND e.aggregate_id=a.id AND e.event_type='APPROVAL_STEP_APPROVED'
           AND e.state='PUBLISHED')=(CASE WHEN v.flowable_definition_key='safeWorkflowV2'
        THEN regexp_count(v.bpmn_xml,'<userTask') -
          (CASE WHEN v.bpmn_xml LIKE '%exclusiveGateway%'
                 AND r.risk_level NOT IN ('HIGH','CRITICAL') THEN 1 ELSE 0 END)
        ELSE 2 END)
      AND v.status IN ('PUBLISHED','RETIRED')
      AND d.tls_mode='VERIFY_FULL'
    ORDER BY t.enqueued_at,t.id LIMIT 1 FOR UPDATE OF t SKIP LOCKED
    """,(rs,i)->new Task(
       rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
       rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
       rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),
       rs.getString(9),rs.getInt(10),rs.getString(11),
       rs.getObject(12,UUID.class),rs.getBytes(13),rs.getBytes(14),
       rs.getString(15),rs.getString(16),rs.getString(17)));
  if(rows.size()!=1)return null;
  Task t=rows.get(0);
  if(!signer.verify(t.org(),t.approval(),t.request(),t.fingerprint(),
       t.hmac(),t.task(),t.ticket()))
   throw new SecurityException("QUEUED_WRITE_TICKET_INVALID");
  try{
   String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
     .digest(t.bpmn().getBytes(StandardCharsets.UTF_8)));
   if(!MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),
       t.bpmnDigest().getBytes(StandardCharsets.US_ASCII)))
    throw new SecurityException("EXECUTION_WORKFLOW_TAMPERED");
   SafeApprovalGraph.requiredReviewers(t.bpmn(),
      List.of("HIGH","CRITICAL").contains(t.risk()));
  }catch(SecurityException e){throw e;}
   catch(Exception e){throw new SecurityException("UNCERTIFIED_EXECUTION_WORKFLOW");}
  int updated=jdbc.update("""
    UPDATE dg_execution_task SET task_state='EXECUTING',
      started_at=now(),lease_owner='certified-write-worker',
      lease_until=now()+interval '90 seconds',
      attempt_no=attempt_no+1,fencing_token=fencing_token+1,
      updated_at=now(),version=version+1
    WHERE org_id=? AND id=? AND task_state='QUEUED' AND attempt_no=0
    """,t.org(),t.task());
  if(updated!=1)throw new IllegalStateException("Write already claimed");
  if(jdbc.update("""
    UPDATE dg_sql_approval SET execution_status='EXECUTING',
      updated_at=now(),version=version+1
    WHERE org_id=? AND id=? AND execution_status='QUEUED'
    """,t.org(),t.approval())!=1)
   throw new IllegalStateException("Approval no longer queued");
  jdbc.update("""
    INSERT INTO dg_execution_attempt
      (id,org_id,task_id,attempt_no,phase)
    VALUES (?,?,?,1,'IN_FLIGHT')
    """,UUID.randomUUID(),t.org(),t.task());
  event(t,"WRITE_ATTEMPT_IN_FLIGHT");
  return t;
 }
 private void event(Task task,String action){
  jdbc.update("""
    INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
    VALUES (?,?,'CERTIFIED_WRITE_WORKER',?,'EXECUTION_TASK',?)
    """,UUID.randomUUID(),task.org(),action,task.task().toString());
 }
}
