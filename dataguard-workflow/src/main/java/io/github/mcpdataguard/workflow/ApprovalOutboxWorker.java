package io.github.mcpdataguard.workflow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opt-in durable platform-to-Flowable bridge. The platform DB is the source of
 * approval authority; Flowable records task progress but NEVER executes SQL.
 * Replays reconcile completed tasks after cross-database partial commits.
 */
@Component
@ConditionalOnProperty(name="dataguard.workflow.outbox.enabled",havingValue="true")
public class ApprovalOutboxWorker {
 private final String jdbcUrl,user,password;
 private final ReviewProcessService workflow;
 private final RuntimeService runtime;
 private final TaskService tasks;
 private CertifiedWorkflowDeployment dynamic;
 public ApprovalOutboxWorker(
     @Value("${dataguard.workflow.outbox.jdbc-url:}") String jdbcUrl,
     @Value("${dataguard.workflow.outbox.user:}") String user,
     @Value("${dataguard.workflow.outbox.password:}") String password,
     ReviewProcessService workflow,RuntimeService runtime,TaskService tasks){
  if(jdbcUrl.isBlank()||user.isBlank()||password.isBlank())
   throw new IllegalStateException("Platform outbox credentials required");
  this.jdbcUrl=jdbcUrl;this.user=user;this.password=password;
  this.workflow=workflow;this.runtime=runtime;this.tasks=tasks;
 }
 @org.springframework.beans.factory.annotation.Autowired
 public ApprovalOutboxWorker(
     @Value("${dataguard.workflow.outbox.jdbc-url:}") String jdbcUrl,
     @Value("${dataguard.workflow.outbox.user:}") String user,
     @Value("${dataguard.workflow.outbox.password:}") String password,
     ReviewProcessService workflow,RuntimeService runtime,TaskService tasks,
     CertifiedWorkflowDeployment dynamic){
  this(jdbcUrl,user,password,workflow,runtime,tasks);
  this.dynamic=dynamic;
 }
 @Scheduled(fixedDelayString="${dataguard.workflow.outbox.poll-ms:10000}")
 public void poll(){
  UUID failedId=null,failedOrg=null;
  try(Connection c=DriverManager.getConnection(jdbcUrl,user,password)){
   c.setAutoCommit(false);
   try(PreparedStatement q=c.prepareStatement("""
     SELECT e.id,e.org_id,e.aggregate_id,e.event_type,
       e.payload->>'actionId',e.payload->>'reviewerId',e.payload->>'stage'
     FROM platform.dg_outbox_event e
     JOIN platform.dg_sql_approval a ON a.org_id=e.org_id AND a.id=e.aggregate_id
     WHERE e.state='PENDING' AND e.available_at<=now() AND
       ((e.event_type='APPROVAL_REQUESTED' AND a.approval_status='PENDING')
        OR (e.event_type='APPROVAL_STEP_APPROVED' AND a.process_instance_id IS NOT NULL)
        OR (e.event_type='APPROVAL_REJECTED' AND a.approval_status IN ('REJECTED','EXPIRED')))
     ORDER BY e.created_at,e.id
     LIMIT 1 FOR UPDATE OF e SKIP LOCKED
     """)){
    try(ResultSet rs=q.executeQuery()){
     if(!rs.next()){c.rollback();return;}
     UUID eventId=rs.getObject(1,UUID.class),org=rs.getObject(2,UUID.class),
       approval=rs.getObject(3,UUID.class);
     failedId=eventId;failedOrg=org;
     String type=rs.getString(4),action=rs.getString(5),
       reviewer=rs.getString(6),stage=rs.getString(7);
     String instanceId=lookupInstance(c,org,approval);
     if("APPROVAL_REQUESTED".equals(type)){
      if(instanceId==null){
       var active=runtime.createProcessInstanceQuery()
         .processInstanceBusinessKey(approval.toString()).singleResult();
       if(active!=null){
        instanceId=active.getId();
       }else if(dynamic!=null && dynamic.enabled()){
        // The version is resolved through this approval's immutable workflow binding.
        try(PreparedStatement definition=c.prepareStatement("""
          SELECT v.bpmn_xml,v.bpmn_digest,r.risk_level FROM platform.dg_sql_approval a
          JOIN platform.dg_workflow_version v ON v.org_id=a.org_id AND v.id=a.workflow_version_id
          JOIN platform.dg_sql_request r ON r.org_id=a.org_id AND r.id=a.request_id
          WHERE a.org_id=? AND a.id=? AND v.status IN ('PUBLISHED','RETIRED')
          """)){
         definition.setObject(1,org);definition.setObject(2,approval);
         try(ResultSet version=definition.executeQuery()){
          if(!version.next())throw new SecurityException("Published workflow missing");
          String xml=version.getString(1);
          String expectedDigest=version.getString(2);
          String actualDigest=java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
             .digest(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          if(expectedDigest==null || !java.security.MessageDigest.isEqual(
             actualDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
             expectedDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
           throw new SecurityException("PUBLISHED_BPMN_DIGEST_MISMATCH");
          instanceId=dynamic.start(xml,approval.toString(),org.toString(),
             ("HIGH".equals(version.getString(3)) || "CRITICAL".equals(version.getString(3))));
         }
        }
       }else{
        instanceId=workflow.start(approval.toString(),org.toString());
       }
       try(PreparedStatement u=c.prepareStatement("""
         UPDATE platform.dg_sql_approval SET process_instance_id=?,updated_at=now()
         WHERE org_id=? AND id=? AND process_instance_id IS NULL
         """)){
        u.setString(1,instanceId);u.setObject(2,org);u.setObject(3,approval);
        if(u.executeUpdate()!=1)throw new IllegalStateException("Concurrent approval creation");
       }
      }
     }else if("APPROVAL_STEP_APPROVED".equals(type)){
      advance(c,org,approval,instanceId,action,reviewer,stage);
     }else if("APPROVAL_REJECTED".equals(type)){
      if(instanceId!=null){
       var active=runtime.createProcessInstanceQuery()
         .processInstanceId(instanceId).singleResult();
       if(active!=null){
        verifyTenant(instanceId,org);
        runtime.deleteProcessInstance(instanceId,"REJECTED");
       }
      }
     }else throw new IllegalStateException("Unexpected approval event");
     try(PreparedStatement done=c.prepareStatement("""
      UPDATE platform.dg_outbox_event
      SET state='PUBLISHED',dispatched_at=now(),attempts=attempts+1
      WHERE id=? AND org_id=? AND state='PENDING'
      """)){
      done.setObject(1,eventId);done.setObject(2,org);
      if(done.executeUpdate()!=1)throw new IllegalStateException("Outbox state changed");
     }
     c.commit();
    }
   }catch(Exception e){c.rollback();throw e;}
  }catch(Exception e){
   // Do not leak event payload or database errors into logs.
   System.err.println("Approval outbox dispatch failed (details redacted)");
   if(failedId!=null)retryFailedEvent(failedId,failedOrg);
  }
 }
 /**
  * A separate transaction survives the failed business transaction. Bounded
  * exponential delays; after 8 attempts FAILED is a dead-letter state.
  */
 private void retryFailedEvent(UUID eventId,UUID organization){
  try(Connection c=DriverManager.getConnection(jdbcUrl,user,password)){
   c.setAutoCommit(false);
   try(PreparedStatement q=c.prepareStatement("""
     SELECT attempts FROM platform.dg_outbox_event
     WHERE id=? AND org_id=? AND state='PENDING' FOR UPDATE
     """)){
    q.setObject(1,eventId);q.setObject(2,organization);
    try(ResultSet rs=q.executeQuery()){
     if(rs.next()){
      int attempts=rs.getInt(1)+1;
      try(PreparedStatement update=c.prepareStatement("""
       UPDATE platform.dg_outbox_event
       SET attempts=?,state=?,available_at=now()+(? * interval '1 second')
       WHERE id=? AND org_id=? AND state='PENDING'
       """)){
       update.setInt(1,attempts);
       update.setString(2,attempts>=8?"FAILED":"PENDING");
       update.setInt(3,Math.min(3600,10*(1<<Math.min(attempts,8))));
       update.setObject(4,eventId);update.setObject(5,organization);
       if(update.executeUpdate()!=1)throw new IllegalStateException("Concurrent outbox change");
      }
      if(attempts>=8){
       try(PreparedStatement audit=c.prepareStatement("""
        INSERT INTO platform.dg_system_event
           (id,org_id,actor,operation,resource_type,resource_id)
        VALUES (?,?,'FLOWABLE_OUTBOX_WORKER','APPROVAL_EVENT_DEAD_LETTER','OUTBOX',?)
        """)){
        audit.setObject(1,UUID.randomUUID());
        audit.setObject(2,organization);
        audit.setString(3,eventId.toString());
        audit.executeUpdate();
       }
       try(PreparedStatement rule=c.prepareStatement("""
        INSERT INTO platform.dg_alert_rule(id,org_id,code,rule,enabled,severity)
        VALUES(?,?,'APPROVAL_OUTBOX_DEAD_LETTER',
          '{"kind":"APPROVAL_OUTBOX_FAILURE"}'::jsonb,true,'HIGH')
        ON CONFLICT(org_id,code) DO NOTHING
        """)){
        rule.setObject(1,UUID.randomUUID());rule.setObject(2,organization);
        rule.executeUpdate();
       }
       try(PreparedStatement alert=c.prepareStatement("""
        INSERT INTO platform.dg_alert_event
           (id,org_id,rule_id,fingerprint,event_state,details)
        SELECT ?,?,r.id,?,'OPEN',jsonb_build_object('attempts',?)
        FROM platform.dg_alert_rule r
        WHERE r.org_id=? AND r.code='APPROVAL_OUTBOX_DEAD_LETTER'
        """)){
        byte[] bytes=java.security.MessageDigest.getInstance("SHA-256")
           .digest(eventId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        alert.setObject(1,UUID.randomUUID());alert.setObject(2,organization);
        alert.setString(3,java.util.HexFormat.of().formatHex(bytes));
        alert.setInt(4,attempts);alert.setObject(5,organization);
        if(alert.executeUpdate()!=1)throw new IllegalStateException("Missing alert rule");
       }
      }
     }
    }
   }
   c.commit();
  }catch(Exception ignored){
   System.err.println("Approval outbox retry update failed (redacted)");
  }
 }
 private String lookupInstance(Connection c,UUID org,UUID approval)throws Exception{
  try(PreparedStatement p=c.prepareStatement("""
   SELECT process_instance_id FROM platform.dg_sql_approval
    WHERE org_id=? AND id=? FOR UPDATE
   """)){
   p.setObject(1,org);p.setObject(2,approval);
   try(ResultSet rs=p.executeQuery()){
    if(!rs.next())throw new IllegalStateException("Approval unavailable");
    return rs.getString(1);
   }
  }
 }
 private void verifyTenant(String instanceId,UUID org){
  if(!org.toString().equals(runtime.getVariable(instanceId,"tenantId")))
   throw new SecurityException("Workflow tenant mismatch");
 }
 private void advance(Connection c,UUID org,UUID approval,String instance,
                      String action,String reviewer,String stage)throws Exception{
  if(instance==null||action==null||reviewer==null||stage==null ||
     !action.matches("[0-9a-f-]{36}")||!reviewer.matches("[0-9a-f-]{36}") ||
     !stage.matches("[1-6]"))
   throw new SecurityException("Invalid approval action");
  UUID actionId=UUID.fromString(action),reviewerId=UUID.fromString(reviewer);
  try(PreparedStatement p=c.prepareStatement("""
    SELECT 1 FROM platform.dg_sql_approval_action
    WHERE org_id=? AND approval_id=? AND id=? AND reviewer_user_id=?
      AND action='APPROVE'
    """)){
   p.setObject(1,org);p.setObject(2,approval);
   p.setObject(3,actionId);p.setObject(4,reviewerId);
   try(ResultSet rs=p.executeQuery()){
    if(!rs.next())throw new SecurityException("Approval action not authorized");
   }
  }
  var active=runtime.createProcessInstanceQuery().processInstanceId(instance).singleResult();
  if(active!=null)verifyTenant(instance,org);
  List<Task> pending=active==null?List.of():tasks.createTaskQuery().processInstanceId(instance).list();
  boolean flexible=active!=null&&active.getProcessDefinitionId().startsWith("safeWorkflowV2:");
  String expected=flexible?"review"+stage:("1".equals(stage)?"ownerReview":"dbaReview");
  if(pending.size()==1&&expected.equals(pending.get(0).getTaskDefinitionKey())){
   Task task=pending.get(0);
   if(task.getAssignee()==null)tasks.claim(task.getId(),reviewer);
   else if(!reviewer.equals(task.getAssignee()))throw new SecurityException("Task owned by another reviewer");
   tasks.complete(task.getId(),Map.of("lastReviewer",reviewer,"sqlExecutionEnabled",false));
   try(PreparedStatement u=c.prepareStatement("""
     UPDATE platform.dg_sql_approval_action SET flowable_task_id=?
     WHERE org_id=? AND approval_id=? AND id=?
     """)){
    u.setString(1,task.getId());u.setObject(2,org);
    u.setObject(3,approval);u.setObject(4,actionId);
    if(u.executeUpdate()!=1)throw new IllegalStateException("Action update conflict");
   }
  }else if(active!=null && pending.size()==1 &&
       (flexible?("review"+(Integer.parseInt(stage)+1))
         :("1".equals(stage)?"dbaReview":"__no_next__"))
        .equals(pending.get(0).getTaskDefinitionKey())){
   // The previous completion succeeded but the outbox commit was interrupted.
  }else if(active==null&&Integer.parseInt(stage)>=2){
   // A completed Flowable instance is no longer in the runtime table.
  }else throw new IllegalStateException("Review stage not ready");
 }
}
