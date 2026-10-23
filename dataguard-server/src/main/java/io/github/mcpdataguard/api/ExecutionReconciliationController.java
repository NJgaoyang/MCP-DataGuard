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
 * Conservative operator reconciliation of ambiguous remote commits.
 * No write retry, no task requeue, and no assertion of success solely
 * from the platform database. Human evidence is external to this service.
 */
@RestController
@RequestMapping("/api/v1/execution-reconciliation")
public class ExecutionReconciliationController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public ExecutionReconciliationController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 public record Attestation(String evidenceRef,String outcome,long expectedVersion){}
 private record Unknown(UUID task,UUID org,UUID approval,long version){}
 private static boolean validEvidence(String s){
  return s!=null&&s.matches("[A-Za-z0-9][A-Za-z0-9_:/.-]{12,240}");
 }
 private UserPrincipal operator(Jwt jwt){
  UserPrincipal actor=users.requireUser(jwt);
  users.requirePermission(actor,"identity:admin");
  return actor;
 }
 @GetMapping
 public List<Map<String,Object>> unknown(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal u=operator(jwt);
  return jdbc.query("""
    SELECT t.id,t.approval_id,t.attempt_no,t.version,t.updated_at
    FROM dg_execution_task t WHERE t.org_id=? AND t.task_state='UNKNOWN'
    ORDER BY t.updated_at DESC LIMIT 100
    """,(rs,i)->Map.<String,Object>of(
       "taskId",rs.getObject(1,UUID.class).toString(),
       "approvalId",rs.getObject(2,UUID.class).toString(),
       "attempts",rs.getInt(3),"version",rs.getLong(4),
       "updatedAt",rs.getTimestamp(5).toInstant().toString()),u.orgId());
 }
 @PostMapping("/{taskId}/attest")
 @Transactional
 public Map<String,Object> attest(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID taskId,@RequestBody Attestation input){
  UserPrincipal u=operator(jwt);
  if(input==null||!validEvidence(input.evidenceRef())||
     !List.of("COMMITTED","NOT_COMMITTED","INDETERMINATE").contains(input.outcome())||
     input.expectedVersion()<0)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid evidence attestation");
  List<Unknown> rows=jdbc.query("""
    SELECT t.id,t.org_id,t.approval_id,t.version
    FROM dg_execution_task t
    WHERE t.org_id=? AND t.id=? AND t.task_state='UNKNOWN'
      AND t.version=? FOR UPDATE
    """,(rs,i)->new Unknown(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
        rs.getObject(3,UUID.class),rs.getLong(4)),
     u.orgId(),taskId,input.expectedVersion());
  if(rows.size()!=1)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Unknown task changed");
  Unknown task=rows.get(0);
  // Stage attestations in immutable audit events. Distinct operators cannot
  // attest the same task twice, and none may have reviewed the original SQL.
  Boolean reviewer=jdbc.queryForObject("""
    SELECT EXISTS(
      SELECT 1 FROM dg_sql_approval_action
      WHERE org_id=? AND approval_id=? AND reviewer_user_id=?
    ) OR EXISTS(
      SELECT 1 FROM dg_sql_approval a JOIN dg_sql_request r
        ON r.org_id=a.org_id AND r.id=a.request_id
      WHERE a.org_id=? AND a.id=? AND r.requester_user_id=?
    )
    """,Boolean.class,u.orgId(),task.approval(),u.userId(),
      u.orgId(),task.approval(),u.userId());
  if(Boolean.TRUE.equals(reviewer))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent reconciliation required");
  Boolean already=jdbc.queryForObject("""
    SELECT EXISTS(
      SELECT 1 FROM dg_system_event
      WHERE org_id=? AND resource_type='EXECUTION_RECONCILIATION'
        AND resource_id=? AND actor=?
    )
    """,Boolean.class,u.orgId(),taskId.toString(),u.userId().toString());
  if(Boolean.TRUE.equals(already))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Operator already attested");
  // Safe literal outcome and evidence with JSON serialization performed by DB,
  // avoiding SQL string interpolation and exposing no SQL or bound values.
  jdbc.update("""
    INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id,detail)
    VALUES(?,?,?,'WRITE_RECONCILIATION_ATTESTED','EXECUTION_RECONCILIATION',?,
      jsonb_build_object('outcome',CAST(? AS text),'evidenceRef',CAST(? AS text),'taskVersion',CAST(? AS bigint)))
    """,UUID.randomUUID(),u.orgId(),u.userId().toString(),taskId.toString(),
    input.outcome(),input.evidenceRef(),task.version());
  List<String> previous=jdbc.query("""
    SELECT detail->>'outcome' FROM dg_system_event
    WHERE org_id=? AND resource_type='EXECUTION_RECONCILIATION'
      AND resource_id=? AND operation='WRITE_RECONCILIATION_ATTESTED'
    ORDER BY created_at,id
    """,(rs,i)->rs.getString(1),u.orgId(),taskId.toString());
  String disposition=previous.size()>=2&&previous.stream().distinct().count()==1
    && !"INDETERMINATE".equals(previous.get(0))
    ? "TWO_PERSON_EVIDENCE_AGREED" : "REQUIRES_FURTHER_INVESTIGATION";
  // Intentionally retain UNKNOWN forever in automated task state:
  // even dual attestation must not authorize re-execution.
  return Map.of("taskId",taskId.toString(),"state","UNKNOWN",
    "attestations",previous.size(),"disposition",disposition,
    "automaticReplayAllowed",false);
 }
}
