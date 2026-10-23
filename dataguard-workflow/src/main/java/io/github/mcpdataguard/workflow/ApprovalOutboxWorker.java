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
 @Scheduled(fixedDelayString="${dataguard.workflow.outbox.poll-ms:10000}")
 public void poll(){
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
        OR (e.event_type='APPROVAL_REJECTED' AND a.approval_status='REJECTED'))
     ORDER BY e.created_at,e.id
     LIMIT 1 FOR UPDATE OF e SKIP LOCKED
     """)){
    try(ResultSet rs=q.executeQuery()){
     if(!rs.next()){c.rollback();return;}
     UUID eventId=rs.getObject(1,UUID.class),org=rs.getObject(2,UUID.class),
       approval=rs.getObject(3,UUID.class);
     String type=rs.getString(4),action=rs.getString(5),
       reviewer=rs.getString(6),stage=rs.getString(7);
     String instanceId=lookupInstance(c,org,approval);
     if("APPROVAL_REQUESTED".equals(type)){
      if(instanceId==null){
       var active=runtime.createProcessInstanceQuery()
         .processInstanceBusinessKey(approval.toString()).singleResult();
       instanceId=active!=null?active.getId():workflow.start(approval.toString(),org.toString());
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
   System.err.println("Approval outbox dispatch failed (details redacted)");
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
     !List.of("1","2").contains(stage))
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
  String expected="1".equals(stage)?"ownerReview":"dbaReview";
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
  }else if("1".equals(stage) && pending.size()==1 &&
       "dbaReview".equals(pending.get(0).getTaskDefinitionKey())){
   // The previous completion succeeded but the outbox commit was interrupted.
  }else if("2".equals(stage)&&active==null){
   // A completed Flowable instance is no longer in the runtime table.
  }else throw new IllegalStateException("Review stage not ready");
 }
}
