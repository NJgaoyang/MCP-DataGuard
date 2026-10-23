package io.github.mcpdataguard.workflow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expired approvals are cancelled and Flowable is notified via the same outbox. */
@Component
@ConditionalOnProperty(name="dataguard.workflow.outbox.enabled",havingValue="true")
public class ApprovalTimeoutWorker {
 private final String url,user,password;
 public ApprovalTimeoutWorker(
   @Value("${dataguard.workflow.outbox.jdbc-url:}") String url,
   @Value("${dataguard.workflow.outbox.user:}") String user,
   @Value("${dataguard.workflow.outbox.password:}") String password){
  this.url=url;this.user=user;this.password=password;
 }
 @Scheduled(fixedDelayString="${dataguard.workflow.timeout.poll-ms:30000}")
 public void expire(){
  if(url.isBlank()||user.isBlank()||password.isBlank())return;
  try(Connection c=DriverManager.getConnection(url,user,password)){
   c.setAutoCommit(false);
   try(PreparedStatement q=c.prepareStatement("""
      SELECT org_id,id FROM platform.dg_sql_approval
      WHERE approval_status='PENDING' AND execution_status='NOT_SCHEDULED'
        AND expires_at<=now() ORDER BY expires_at,id LIMIT 25
      FOR UPDATE SKIP LOCKED
     """);ResultSet rows=q.executeQuery()){
    while(rows.next()){
     UUID org=rows.getObject(1,UUID.class),id=rows.getObject(2,UUID.class);
     try(PreparedStatement u=c.prepareStatement("""
       UPDATE platform.dg_sql_approval
       SET approval_status='EXPIRED',execution_status='CANCELLED',
         decided_at=now(),updated_at=now(),version=version+1
       WHERE org_id=? AND id=? AND approval_status='PENDING'
       """)){
      u.setObject(1,org);u.setObject(2,id);
      if(u.executeUpdate()!=1)continue;
     }
     try(PreparedStatement out=c.prepareStatement("""
       INSERT INTO platform.dg_outbox_event
         (id,org_id,aggregate_type,aggregate_id,event_type,payload)
       VALUES (?,?,'SQL_APPROVAL',?,'APPROVAL_REJECTED','{}'::jsonb)
       """)){
      out.setObject(1,UUID.randomUUID());
      out.setObject(2,org);out.setObject(3,id);out.executeUpdate();
     }
     try(PreparedStatement audit=c.prepareStatement("""
       INSERT INTO platform.dg_system_event
        (id,org_id,actor,operation,resource_type,resource_id)
       VALUES (?,?,'APPROVAL_TIMEOUT_WORKER','SQL_APPROVAL_EXPIRED','SQL_APPROVAL',?)
       """)){
      audit.setObject(1,UUID.randomUUID());
      audit.setObject(2,org);audit.setString(3,id.toString());audit.executeUpdate();
     }
    }
   }
   c.commit();
  }catch(Exception e){
   System.err.println("Approval expiry check failed (redacted)");
  }
 }
}
