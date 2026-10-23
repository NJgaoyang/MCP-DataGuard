package io.github.mcpdataguard.api;

import io.github.mcpdataguard.mcp.ApprovedExecutionQueue;
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
 * Privileged queue release. Cannot supply SQL, tenant, signed ticket, or HMAC
 * over HTTP. A separate operator (not requester or reviewer) must authorize.
 */
@RestController
@RequestMapping("/api/v1/execution-tasks")
public class ExecutionReleaseController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 private final ApprovedExecutionQueue queue;
 public ExecutionReleaseController(JdbcTemplate jdbc,UserPrincipalResolver users,
                                   ApprovedExecutionQueue queue){
  this.jdbc=jdbc;this.users=users;this.queue=queue;
 }
 public record ReleaseInput(long expectedVersion){}
 private record ImmutableRequest(String fingerprint,String hmac){}
 @PostMapping("/{approvalId}/queue")
 @Transactional
 public Map<String,Object> release(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID approvalId,@RequestBody ReleaseInput input){
  UserPrincipal actor=users.requireUser(jwt);
  users.requirePermission(actor,"identity:admin");
  if(input==null || input.expectedVersion()<0)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid execution release version");
  List<ImmutableRequest> immutable=jdbc.query("""
    SELECT r.sql_fingerprint,p.canonical_payload_hmac
    FROM dg_sql_approval a
    JOIN dg_sql_request r ON r.org_id=a.org_id AND r.id=a.request_id
    JOIN dg_sql_request_payload p ON p.org_id=r.org_id AND p.request_id=r.id
    WHERE a.org_id=? AND a.id=? AND a.version=?
      AND a.approval_status='APPROVED' AND a.execution_status='NOT_SCHEDULED'
      AND a.expires_at>now() AND r.requester_user_id IS DISTINCT FROM ?
      AND NOT EXISTS(
       SELECT 1 FROM dg_sql_approval_action action
       WHERE action.org_id=a.org_id AND action.approval_id=a.id
         AND action.reviewer_user_id=?
      ) FOR UPDATE OF a
    """,(rs,i)->new ImmutableRequest(rs.getString(1),rs.getString(2)),
     actor.orgId(),approvalId,input.expectedVersion(),actor.userId(),actor.userId());
  if(immutable.size()!=1)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval unavailable for independent release");
  var original=immutable.get(0);
  var released=queue.enqueue(actor.orgId(),approvalId,
    original.fingerprint(),original.hmac());
  return Map.of("queued",released.queued(),"code",released.code(),
    "taskId",released.taskId()==null?"":released.taskId().toString(),
    "sqlExecutionEnabled",false);
 }
}
