package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Tenant-isolated dead-letter incident triage; does not bypass approval. */
@RestController
@RequestMapping("/api/v1/workflow-events")
public class WorkflowEventController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public WorkflowEventController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 private UserPrincipal admin(Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);
  users.requirePermission(u,"identity:admin");
  return u;
 }
 @GetMapping("/dead-letters")
 public List<Map<String,Object>> dead(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal u=admin(jwt);
  return jdbc.query("""
   SELECT id,event_type,aggregate_id,attempts,created_at
   FROM dg_outbox_event
   WHERE org_id=? AND state='FAILED'
     AND event_type IN ('APPROVAL_REQUESTED','APPROVAL_STEP_APPROVED','APPROVAL_REJECTED')
   ORDER BY created_at DESC,id DESC LIMIT 100
   """,(rs,i)->Map.<String,Object>of("id",rs.getString(1),"type",rs.getString(2),
      "approvalId",rs.getString(3),"attempts",rs.getInt(4),
      "createdAt",rs.getTimestamp(5).toInstant().toString()),u.orgId());
 }
 @PostMapping("/{eventId}/retry")
 @Transactional
 public Map<String,Object> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID eventId){
  UserPrincipal u=admin(jwt);
  // Retry is allowed only for still-valid approval requests. Never revive a
  // rejected, cancelled or expired approval through a dead-letter replay.
  int updated=jdbc.update("""
   UPDATE dg_outbox_event e SET state='PENDING',attempts=0,available_at=now()
   WHERE e.id=? AND e.org_id=? AND e.state='FAILED'
     AND e.event_type IN ('APPROVAL_REQUESTED','APPROVAL_STEP_APPROVED','APPROVAL_REJECTED')
     AND EXISTS(SELECT 1 FROM dg_sql_approval a
      WHERE a.id=e.aggregate_id AND a.org_id=e.org_id
        AND a.expires_at>now() AND a.execution_status='NOT_SCHEDULED'
        AND (a.approval_status='PENDING' OR
         (a.approval_status='APPROVED' AND e.event_type='APPROVAL_STEP_APPROVED')))
   """,eventId,u.orgId());
  if(updated!=1)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Dead letter unavailable for safe retry");
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,'APPROVAL_DEAD_LETTER_RETRIED','OUTBOX',?)
   """,UUID.randomUUID(),u.orgId(),u.userId().toString(),eventId.toString());
  return Map.of("id",eventId.toString(),"state","PENDING","sqlExecutionEnabled",false);
 }
}
