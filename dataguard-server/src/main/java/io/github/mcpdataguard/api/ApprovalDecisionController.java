package io.github.mcpdataguard.api;

import io.github.mcpdataguard.workflow.contract.SafeApprovalGraph;
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
 * Only active, stage-matched human reviewers may approve or reject.
 * Human decisions never directly authorize SQL execution; outbox synchronization
 * must complete before a separate write execution can be released.
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
  UserPrincipal u=users.requireUser(jwt);
  if(input==null || input.expectedVersion()<0 || input.reason()==null ||
      input.reason().isBlank() || input.reason().length()>1000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Rejection reason required");
  // A reviewer is authorized by the CURRENT certified workflow stage,
  // not by global administrator permissions.
  record RejectionScope(boolean independent,String bpmn,String risk){}
  List<RejectionScope> eligible=jdbc.query("""
   SELECT COALESCE(r.requester_user_id,
      (SELECT agent.owner_user_id FROM dg_agent agent
       WHERE agent.org_id=r.org_id AND agent.id=r.agent_id))
      IS DISTINCT FROM ?,v.bpmn_xml,r.risk_level
   FROM dg_sql_approval a JOIN dg_sql_request r
      ON r.org_id=a.org_id AND r.id=a.request_id
   JOIN dg_workflow_version v ON v.org_id=a.org_id AND v.id=a.workflow_version_id
   WHERE a.org_id=? AND a.id=? AND a.approval_status='PENDING'
     AND a.execution_status='NOT_SCHEDULED' AND a.expires_at>now()
     AND a.process_instance_id IS NOT NULL AND a.version=?
     AND v.status IN ('PUBLISHED','RETIRED') FOR UPDATE OF a
   """,(rs,i)->new RejectionScope(rs.getBoolean(1),rs.getString(2),rs.getString(3)),
     u.userId(),u.orgId(),approvalId,input.expectedVersion());
  if(eligible.size()!=1)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval unavailable or stale");
  RejectionScope scope=eligible.get(0);
  if(!scope.independent())
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Self review is forbidden");
  int reviewed=jdbc.queryForObject("""
   SELECT count(*) FROM dg_sql_approval_action
   WHERE org_id=? AND approval_id=? AND action='APPROVE'
   """,Integer.class,u.orgId(),approvalId);
  Boolean repeat=jdbc.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM dg_sql_approval_action
     WHERE org_id=? AND approval_id=? AND action='APPROVE'
       AND reviewer_user_id=?)
   """,Boolean.class,u.orgId(),approvalId,u.userId());
  if(Boolean.TRUE.equals(repeat))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent reviewer required");
  boolean highRisk=List.of("HIGH","CRITICAL").contains(scope.risk());
  String role;
  try{
   if(reviewed>=SafeApprovalGraph.requiredReviewers(scope.bpmn(),highRisk))
    throw new SecurityException("No remaining review stage");
   role=SafeApprovalGraph.roleAt(scope.bpmn(),reviewed+1,highRisk);
  }catch(SecurityException ex){
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Uncertified reviewer stage");
  }
  Boolean assigned=jdbc.queryForObject("""
   SELECT EXISTS(
     SELECT 1 FROM dg_user_role ur JOIN dg_role r
       ON r.org_id=ur.org_id AND r.id=ur.role_id
     WHERE ur.org_id=? AND ur.user_id=? AND r.code=?
   )
   """,Boolean.class,u.orgId(),u.userId(),role);
  if(!Boolean.TRUE.equals(assigned))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Reviewer role not assigned for current step");
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
 /**
  * Administrative cancellation is independent of task approval. It may only
  * cancel a still-pending, never-scheduled request and is audit/outbox backed.
  * The consumer terminates the tenant-scoped Flowable instance asynchronously.
  */
 @PostMapping("/{approvalId}/cancel")
 @Transactional
 public Map<String,Object> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID approvalId,
                                   @RequestBody DecisionInput input){
  UserPrincipal u=actor(jwt);
  if(input==null || input.expectedVersion()<0 || input.reason()==null ||
     input.reason().isBlank() || input.reason().length()>1000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cancellation reason required");
  List<UUID> pending=jdbc.query("""
    SELECT id FROM dg_sql_approval
    WHERE org_id=? AND id=? AND approval_status='PENDING'
      AND execution_status='NOT_SCHEDULED' AND version=?
    FOR UPDATE
    """,(rs,i)->rs.getObject(1,UUID.class),u.orgId(),approvalId,input.expectedVersion());
  if(pending.size()!=1)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval unavailable or already scheduled");
  int updated=jdbc.update("""
    UPDATE dg_sql_approval SET approval_status='CANCELLED',
      execution_status='CANCELLED',decided_at=now(),updated_at=now(),version=version+1
    WHERE org_id=? AND id=? AND approval_status='PENDING'
      AND execution_status='NOT_SCHEDULED' AND version=?
    """,u.orgId(),approvalId,input.expectedVersion());
  if(updated!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Concurrent cancellation");
  jdbc.update("""
    INSERT INTO dg_sql_approval_action
      (id,org_id,approval_id,reviewer_user_id,flowable_task_id,action,comment_text)
    VALUES (?,?,?,?,?,'CANCEL',?)
    """,UUID.randomUUID(),u.orgId(),approvalId,u.userId(),
      "platform:admin-cancel",input.reason().strip());
  auditDecision(u,"SQL_APPROVAL_CANCELLED",approvalId);
  jdbc.update("""
    INSERT INTO dg_outbox_event(id,org_id,aggregate_type,aggregate_id,event_type,payload)
    VALUES (?,?,'SQL_APPROVAL',?,'APPROVAL_CANCELLED','{}'::jsonb)
    """,UUID.randomUUID(),u.orgId(),approvalId);
  return Map.of("approvalId",approvalId.toString(),"approvalStatus","CANCELLED",
    "executionStatus","CANCELLED","sqlExecutionEnabled",false);
 }
 /**
  * Tenant-scoped personal approval inbox. A business reviewer needs only the
  * role assigned to the CURRENT stage; no global identity:admin permission.
  * Raw SQL, secret payloads and unrelated organizations are never returned.
  */
 @GetMapping("/my-inbox")
 public List<Map<String,Object>> myInbox(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);
  List<String> reviewerRoles=jdbc.query("""
    SELECT r.code FROM dg_user_role ur JOIN dg_role r
      ON r.org_id=ur.org_id AND r.id=ur.role_id
    WHERE ur.org_id=? AND ur.user_id=?
    """,(rs,i)->rs.getString(1),u.orgId(),u.userId());
  if(reviewerRoles.isEmpty())return List.of();
  record Candidate(UUID id,long version,java.time.Instant expires,
     String risk,String bpmn,int stages){}
  List<Candidate> candidates=jdbc.query("""
    SELECT a.id,a.version,a.expires_at,r.risk_level,v.bpmn_xml,
      (SELECT count(*) FROM dg_sql_approval_action x
       WHERE x.org_id=a.org_id AND x.approval_id=a.id AND x.action='APPROVE') AS stages
    FROM dg_sql_approval a JOIN dg_sql_request r
      ON r.org_id=a.org_id AND r.id=a.request_id
    JOIN dg_workflow_version v
      ON v.org_id=a.org_id AND v.id=a.workflow_version_id
    WHERE a.org_id=? AND a.approval_status='PENDING'
      AND a.execution_status='NOT_SCHEDULED' AND a.expires_at>now()
      AND a.process_instance_id IS NOT NULL
      AND v.status IN ('PUBLISHED','RETIRED')
      AND COALESCE(r.requester_user_id,
        (SELECT agent.owner_user_id FROM dg_agent agent
         WHERE agent.org_id=r.org_id AND agent.id=r.agent_id))
        IS DISTINCT FROM ?
      AND NOT EXISTS(SELECT 1 FROM dg_sql_approval_action x
       WHERE x.org_id=a.org_id AND x.approval_id=a.id
         AND x.action='APPROVE' AND x.reviewer_user_id=?)
    ORDER BY a.created_at,a.id LIMIT 100
    """,(rs,i)->new Candidate(rs.getObject(1,UUID.class),rs.getLong(2),
       rs.getTimestamp(3).toInstant(),rs.getString(4),rs.getString(5),rs.getInt(6)),
       u.orgId(),u.userId(),u.userId());
  List<Map<String,Object>> result=new java.util.ArrayList<>();
  for(Candidate c:candidates){
   boolean high=List.of("HIGH","CRITICAL").contains(c.risk());
   try{
    if(c.stages()>=SafeApprovalGraph.requiredReviewers(c.bpmn(),high))continue;
    String role=SafeApprovalGraph.roleAt(c.bpmn(),c.stages()+1,high);
    if(!reviewerRoles.contains(role))continue;
    result.add(Map.of("approvalId",c.id().toString(),
      "expectedVersion",c.version(),"stage",c.stages()+1,
      "requiredRole",role,"risk",c.risk(),"expiresAt",c.expires().toString()));
   }catch(SecurityException ignored){
    // Invalid/uncertified BPMN cannot create an actionable review item.
   }
  }
  return result;
 }
 @PostMapping("/{approvalId}/approve")
 @Transactional
 public Map<String,Object> approve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID approvalId,
                                    @RequestBody DecisionInput input){
  UserPrincipal u=users.requireUser(jwt);
  if(input==null || input.expectedVersion()<0 || input.reason()==null ||
     input.reason().isBlank() || input.reason().length()>1000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review reason required");
  record ReviewScope(boolean independent,String bpmn,String risk){}
  List<ReviewScope> eligible=jdbc.query("""
   SELECT COALESCE(r.requester_user_id,
       (SELECT agent.owner_user_id FROM dg_agent agent
        WHERE agent.org_id=r.org_id AND agent.id=r.agent_id))
       IS DISTINCT FROM ? AS eligible,
      v.bpmn_xml,r.risk_level
   FROM dg_sql_approval a
   JOIN dg_sql_request r ON r.org_id=a.org_id AND r.id=a.request_id
   JOIN dg_workflow_version v ON v.org_id=a.org_id AND v.id=a.workflow_version_id
   WHERE a.org_id=? AND a.id=? AND a.approval_status='PENDING'
     AND a.execution_status='NOT_SCHEDULED' AND a.expires_at>now()
     AND a.process_instance_id IS NOT NULL AND a.version=?
     AND v.status IN ('PUBLISHED','RETIRED') FOR UPDATE OF a
   """,(rs,i)->new ReviewScope(rs.getBoolean(1),rs.getString(2),rs.getString(3)),
      u.userId(),u.orgId(),approvalId,input.expectedVersion());
  if(eligible.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Workflow not started or stale");
  ReviewScope scope=eligible.get(0);
  if(!scope.independent())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Self-review prohibited");
  boolean highRisk=List.of("HIGH","CRITICAL").contains(scope.risk());
  int requiredStages;
  try{requiredStages=SafeApprovalGraph.requiredReviewers(scope.bpmn(),highRisk);}
  catch(SecurityException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Uncertified workflow graph");}
  List<UUID> previous=jdbc.query("""
   SELECT reviewer_user_id FROM dg_sql_approval_action
   WHERE org_id=? AND approval_id=? AND action='APPROVE'
   ORDER BY acted_at,id LIMIT 2
   """,(rs,i)->rs.getObject(1,UUID.class),u.orgId(),approvalId);
  if(previous.size()>=requiredStages)throw new ResponseStatusException(HttpStatus.CONFLICT,"Review already complete");
  if(previous.contains(u.userId()))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent second reviewer required");
  int nextStage=previous.size()+1;
  // Every supported workflow, including legacy two-stage, must bind
  // approval to the certified reviewer role for its CURRENT stage.
  // An organization administrator is not implicitly an approver.
  String requiredRole;
  try{requiredRole=SafeApprovalGraph.roleAt(scope.bpmn(),nextStage,highRisk);}
  catch(SecurityException ex){
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Uncertified reviewer stage");
  }
  Boolean member=jdbc.queryForObject("""
    SELECT EXISTS(
     SELECT 1 FROM dg_user_role ur JOIN dg_role role
       ON role.org_id=ur.org_id AND role.id=ur.role_id
     WHERE ur.org_id=? AND ur.user_id=? AND role.code=?
    )
    """,Boolean.class,u.orgId(),u.userId(),requiredRole);
  if(!Boolean.TRUE.equals(member))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Reviewer role not assigned for current step");
  UUID actionId=UUID.randomUUID();
  jdbc.update("""
   INSERT INTO dg_sql_approval_action
    (id,org_id,approval_id,reviewer_user_id,flowable_task_id,action,comment_text)
   VALUES (?,?,?,?,?,'APPROVE',?)
   """,actionId,u.orgId(),approvalId,u.userId(),"platform:awaiting-flowable",
      input.reason().strip());
  if(nextStage==requiredStages){
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
    "approvalStatus",nextStage==requiredStages?"APPROVED":"PENDING",
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
