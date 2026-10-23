package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Visibility-only SQL/approval monitoring; SQL text and credentials are never exposed. */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsReadController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public OperationsReadController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 private UserPrincipal admin(Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);users.requirePermission(u,"identity:admin");return u;
 }
 private static void page(int limit,int offset){
  if(limit<1||limit>100||offset<0||offset>100000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
 }
 @GetMapping("/requests")
 public List<Map<String,Object>> requests(@AuthenticationPrincipal Jwt jwt,
     @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset){
  UserPrincipal u=admin(jwt);page(limit,offset);
  return jdbc.query("""
    SELECT id,agent_id,datasource_id,tool_name,operation,sql_fingerprint,
      decision,request_status,received_at
    FROM dg_sql_request WHERE org_id=?
    ORDER BY received_at DESC,id DESC LIMIT ? OFFSET ?
    """,(rs,i)->{
     Map<String,Object> m=new java.util.LinkedHashMap<>();
     for(String col:List.of("id","agent_id","datasource_id","tool_name","operation",
         "sql_fingerprint","decision","request_status"))m.put(col,rs.getString(col));
     m.put("receivedAt",rs.getTimestamp("received_at").toInstant().toString());
     return m;
    },u.orgId(),limit,offset);
 }
 @GetMapping("/approvals")
 public List<Map<String,Object>> approvals(@AuthenticationPrincipal Jwt jwt,
     @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset){
  UserPrincipal u=admin(jwt);page(limit,offset);
  return jdbc.query("""
    SELECT a.id,a.request_id,a.approval_status,a.execution_status,a.expires_at,
      a.created_at,a.version FROM dg_sql_approval a WHERE a.org_id=?
    ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?
    """,(rs,i)->Map.<String,Object>of(
      "id",rs.getString("id"),"requestId",rs.getString("request_id"),
      "approvalStatus",rs.getString("approval_status"),
      "executionStatus",rs.getString("execution_status"),
      "expiresAt",rs.getTimestamp("expires_at").toInstant().toString(),
      "createdAt",rs.getTimestamp("created_at").toInstant().toString(),
      "version",rs.getLong("version")),
      u.orgId(),limit,offset);
 }
 @GetMapping("/summary")
 public Map<String,Object> summary(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal u=admin(jwt);
  Long denied=jdbc.queryForObject("""
   SELECT count(*) FROM dg_sql_request WHERE org_id=? AND request_status='DENIED'
    """,Long.class,u.orgId());
  Long pending=jdbc.queryForObject("""
   SELECT count(*) FROM dg_sql_approval WHERE org_id=? AND approval_status='PENDING'
    """,Long.class,u.orgId());
  Long alerts=jdbc.queryForObject("""
   SELECT count(*) FROM dg_alert_event WHERE org_id=? AND event_state='OPEN'
    """,Long.class,u.orgId());
  return Map.of("deniedRequests",denied,"pendingApprovals",pending,"openAlerts",alerts,
    "sqlExecutionEnabled",false);
 }
}
