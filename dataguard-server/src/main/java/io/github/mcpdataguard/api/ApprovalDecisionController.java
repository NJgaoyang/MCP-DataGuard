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

/**
 * Safe approval rejection/cancellation workflow, independent of the write executor.
 * APPROVE is intentionally not exposed: Flowable task identity and signed execution
 * authorizations are not yet certified. Rejecting always leaves execution blocked.
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalDecisionController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public ApprovalDecisionController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 public record DecisionInput(String reason,long expectedVersion){}
 private UserPrincipal actor(Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);
  users.requirePermission(u,"identity:admin");
  return u;
 }
 @PostMapping("/{approvalId}/reject")
 @Transactional
 public Map<String,Object> reject(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID approvalId,
                                    @RequestBody DecisionInput input){
  UserPrincipal u=actor(jwt);
  if(input==null || input.expectedVersion()<0 || input.reason()==null ||
      input.reason().isBlank() || input.reason().length()>1000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Rejection reason required");
  // Lock pending approval and verify reviewer is not the request's originating user.
  List<Boolean> eligible=jdbc.query("""
   SELECT r.requester_user_id IS DISTINCT FROM ? AS eligible
   FROM dg_sql_approval a JOIN dg_sql_request r ON r.org_id=a.org_id AND r.id=a.request_id
   WHERE a.org_id=? AND a.id=? AND a.approval_status='PENDING'
     AND a.execution_status='NOT_SCHEDULED' AND a.expires_at>now()
     AND a.version=? FOR UPDATE OF a
   """,(rs,i)->rs.getBoolean("eligible"),u.userId(),u.orgId(),approvalId,input.expectedVersion());
  if(eligible.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval unavailable or stale");
  if(!eligible.get(0))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Self review is forbidden");
  int updated=jdbc.update("""
   UPDATE dg_sql_approval
   SET approval_status='REJECTED',execution_status='CANCELLED',
     decided_at=now(),updated_at=now(),version=version+1
   WHERE org_id=? AND id=? AND approval_status='PENDING'
     AND execution_status='NOT_SCHEDULED' AND version=?
   """,u.orgId(),approvalId,input.expectedVersion());
  if(updated!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Concurrent approval change");
  jdbc.update("""
   INSERT INTO dg_sql_approval_action
     (id,org_id,approval_id,reviewer_user_id,flowable_task_id,action,comment_text)
   VALUES (?,?,?,?,?,'REJECT',?)
   """,UUID.randomUUID(),u.orgId(),approvalId,u.userId(),
       "platform:manual-rejection",input.reason().strip());
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,'SQL_APPROVAL_REJECTED','SQL_APPROVAL',?)
   """,UUID.randomUUID(),u.orgId(),u.userId().toString(),approvalId.toString());
  jdbc.update("""
    INSERT INTO dg_outbox_event(id,org_id,aggregate_type,aggregate_id,event_type,payload)
    VALUES (?,?,'SQL_APPROVAL',?,'APPROVAL_REJECTED','{}'::jsonb)
    """,UUID.randomUUID(),u.orgId(),approvalId);
  return Map.of("approvalId",approvalId.toString(),"approvalStatus","REJECTED",
     "executionStatus","CANCELLED","sqlExecutionEnabled",false);
 }
 @PostMapping("/{approvalId}/approve")
 @Transactional
 public Map<String,Object> approve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID approvalId,
                                    @RequestBody DecisionInput input){
  UserPrincipal u=actor(jwt);
  if(input==null || input.expectedVersion()<0 || input.reason()==null ||
     input.reason().isBlank() || input.reason().length()>1000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review reason required");
  List<Boolean> eligible=jdbc.query("""
   SELECT r.requester_user_id IS DISTINCT FROM ? AS eligible
   FROM dg_sql_approval a JOIN dg_sql_request r ON r.org_id=a.org_id AND r.id=a.request_id
   WHERE a.org_id=? AND a.id=? AND a.approval_status='PENDING'
     AND a.execution_status='NOT_SCHEDULED' AND a.expires_at>now()
     AND a.process_instance_id IS NOT NULL AND a.version=? FOR UPDATE OF a
   """,(rs,i)->rs.getBoolean(1),u.userId(),u.orgId(),approvalId,input.expectedVersion());
  if(eligible.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Workflow not started or stale");
  if(!eligible.get(0))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Self-review prohibited");
  List<UUID> previous=jdbc.query("""
   SELECT reviewer_user_id FROM dg_sql_approval_action
   WHERE org_id=? AND approval_id=? AND action='APPROVE'
   ORDER BY acted_at,id LIMIT 2
   """,(rs,i)->rs.getObject(1,UUID.class),u.orgId(),approvalId);
  if(previous.size()>1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Review already complete");
  if(previous.contains(u.userId()))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent second reviewer required");
  int nextStage=previous.size()+1;
  UUID actionId=UUID.randomUUID();
  jdbc.update("""
   INSERT INTO dg_sql_approval_action
    (id,org_id,approval_id,reviewer_user_id,flowable_task_id,action,comment_text)
   VALUES (?,?,?,?,?,'APPROVE',?)
   """,actionId,u.orgId(),approvalId,u.userId(),"platform:awaiting-flowable",
      input.reason().strip());
  if(nextStage==2){
   int updated=jdbc.update("""
    UPDATE dg_sql_approval
    SET approval_status='APPROVED',decided_at=now(),updated_at=now(),version=version+1
    WHERE org_id=? AND id=? AND approval_status='PENDING' AND version=?
    """,u.orgId(),approvalId,input.expectedVersion());
   if(updated!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval changed");
  }else{
   int updated=jdbc.update("""
    UPDATE dg_sql_approval SET version=version+1,updated_at=now()
    WHERE org_id=? AND id=? AND approval_status='PENDING' AND version=?
    """,u.orgId(),approvalId,input.expectedVersion());
   if(updated!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval changed");
  }
  String eventPayload="{\"actionId\":\""+actionId+"\",\"reviewerId\":\""+u.userId()+
    "\",\"stage\":"+nextStage+"}";
  jdbc.update("""
   INSERT INTO dg_outbox_event(id,org_id,aggregate_type,aggregate_id,event_type,payload)
   VALUES (?,?,'SQL_APPROVAL',?,'APPROVAL_STEP_APPROVED',CAST(? AS jsonb))
   """,UUID.randomUUID(),u.orgId(),approvalId,eventPayload);
  auditDecision(u,"SQL_APPROVAL_STAGE_APPROVED",approvalId);
  return Map.of("approvalId",approvalId.toString(),"stage",nextStage,
    "approvalStatus",nextStage==2?"APPROVED":"PENDING",
    "executionStatus","NOT_SCHEDULED","executionEnabled",false);
 }
 private void auditDecision(UserPrincipal u,String operation,UUID id){
  jdbc.update("""
    INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
    VALUES (?,?,?,?,'SQL_APPROVAL',?)
    """,UUID.randomUUID(),u.orgId(),u.userId().toString(),operation,id.toString());
 }
 @GetMapping("/{approvalId}/actions")
 public List<Map<String,String>> actions(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID approvalId){
  UserPrincipal u=actor(jwt);
  return jdbc.query("""
   SELECT x.action,x.comment_text,x.acted_at,x.reviewer_user_id
   FROM dg_sql_approval_action x JOIN dg_sql_approval a
     ON a.org_id=x.org_id AND a.id=x.approval_id
   WHERE x.org_id=? AND x.approval_id=?
   ORDER BY x.acted_at,x.id LIMIT 100
   """,(rs,i)->{
    Map<String,String> row=new java.util.LinkedHashMap<>();
    row.put("action",rs.getString("action"));
    row.put("comment",rs.getString("comment_text"));
    row.put("reviewerId",rs.getString("reviewer_user_id"));
    row.put("actedAt",rs.getTimestamp("acted_at").toInstant().toString());
    return row;
   },u.orgId(),approvalId);
 }
}
