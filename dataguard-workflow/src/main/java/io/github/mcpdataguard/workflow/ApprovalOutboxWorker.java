package io.github.mcpdataguard.workflow;

import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opt-in platform-to-Flowable outbox bridge.
 * Consumes only APPROVAL_REQUESTED events, not arbitrary commands.
 * The two PostgreSQL schemas are independently committed; retry reconciles
 * an active Flowable instance with its platform approval row.
 */
@Component
@ConditionalOnProperty(name="dataguard.workflow.outbox.enabled",havingValue="true")
public class ApprovalOutboxWorker {
 private final String jdbcUrl,user,password;
 private final ReviewProcessService workflow;
 private final RuntimeService runtime;
 public ApprovalOutboxWorker(
     @Value("${dataguard.workflow.outbox.jdbc-url:}") String jdbcUrl,
     @Value("${dataguard.workflow.outbox.user:}") String user,
     @Value("${dataguard.workflow.outbox.password:}") String password,
     ReviewProcessService workflow,RuntimeService runtime){
  if(jdbcUrl.isBlank()||user.isBlank()||password.isBlank())
   throw new IllegalStateException("Platform outbox credentials required");
  this.jdbcUrl=jdbcUrl;this.user=user;this.password=password;
  this.workflow=workflow;this.runtime=runtime;
 }
 @Scheduled(fixedDelayString="${dataguard.workflow.outbox.poll-ms:10000}")
 public void poll(){
  try(Connection c=DriverManager.getConnection(jdbcUrl,user,password)){
   c.setAutoCommit(false);
   // A row lock prevents competing workers claiming the same event.
   try(PreparedStatement q=c.prepareStatement("""
     SELECT e.id,e.org_id,e.aggregate_id
     FROM platform.dg_outbox_event e
     JOIN platform.dg_sql_approval a
       ON a.org_id=e.org_id AND a.id=e.aggregate_id
     WHERE e.event_type='APPROVAL_REQUESTED' AND e.state='PENDING'
       AND e.available_at<=now() AND a.approval_status='PENDING'
     ORDER BY e.created_at,e.id
     LIMIT 1 FOR UPDATE OF e SKIP LOCKED
     """)){
    try(ResultSet rs=q.executeQuery()){
     if(!rs.next()){c.rollback();return;}
     UUID eventId=rs.getObject(1,UUID.class);
     UUID org=rs.getObject(2,UUID.class);
     UUID approval=rs.getObject(3,UUID.class);
     String businessKey=approval.toString(),instanceId=null;
     try(PreparedStatement existing=c.prepareStatement("""
      SELECT process_instance_id FROM platform.dg_sql_approval
      WHERE org_id=? AND id=? FOR UPDATE
      """)){
      existing.setObject(1,org);existing.setObject(2,approval);
      try(ResultSet a=existing.executeQuery()){
       if(!a.next())throw new IllegalStateException("Approval missing");
       instanceId=a.getString(1);
      }
     }
     if(instanceId==null){
      var active=runtime.createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey).singleResult();
      instanceId=active!=null?active.getId():workflow.start(businessKey,org.toString());
      try(PreparedStatement update=c.prepareStatement("""
       UPDATE platform.dg_sql_approval SET process_instance_id=?,updated_at=now()
       WHERE org_id=? AND id=? AND process_instance_id IS NULL
       """)){
       update.setString(1,instanceId);update.setObject(2,org);update.setObject(3,approval);
       if(update.executeUpdate()!=1)throw new IllegalStateException("Approval claim conflict");
      }
     }
     try(PreparedStatement done=c.prepareStatement("""
      UPDATE platform.dg_outbox_event
      SET state='PUBLISHED',dispatched_at=now(),attempts=attempts+1
      WHERE id=? AND org_id=? AND state='PENDING'
      """)){
      done.setObject(1,eventId);done.setObject(2,org);
      if(done.executeUpdate()!=1)throw new IllegalStateException("Outbox claim conflict");
     }
     c.commit();
    }
   }catch(Exception e){c.rollback();throw e;}
  }catch(Exception e){
   // No error detail or credentials may be logged; retry on next schedule.
   System.err.println("Approval outbox cycle failed (redacted)");
  }
 }
}
