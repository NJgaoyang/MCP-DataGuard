package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** OIDC registration is explicit; only datasource discovery grants are supported.
 * No SQL read/write execution is enabled by registration. */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentEnrollmentController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public AgentEnrollmentController(JdbcTemplate jdbc,UserPrincipalResolver users){this.jdbc=jdbc;this.users=users;}
 public record PermissionInput(UUID datasourceId,String action,Instant expiresAt){}
 private UserPrincipal actor(Jwt jwt,String permission){
  UserPrincipal u=users.requireUser(jwt);users.requirePermission(u,permission);return u;
 }
 @PostMapping("/{agentId}/datasource-permissions")
 @Transactional
 public Map<String,String> grant(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID agentId,
                                     @RequestBody PermissionInput input){
  UserPrincipal u=actor(jwt,"agent:permission:write");
  Instant now=Instant.now();
  if(input==null || input.datasourceId()==null ||
     !Set.of("ALLOW","DENY").contains(input.action()) ||
     (input.expiresAt()!=null && (!input.expiresAt().isAfter(now) ||
        input.expiresAt().isAfter(now.plusSeconds(365L*86400)))))
   throw bad("Permission invalid");
  Boolean eligible=jdbc.queryForObject("""
   SELECT EXISTS(
    SELECT 1 FROM dg_agent a JOIN dg_datasource d ON d.org_id=a.org_id
     WHERE a.org_id=? AND a.id=? AND a.status='ACTIVE' AND d.id=?)
   """,Boolean.class,u.orgId(),agentId,input.datasourceId());
  if(!Boolean.TRUE.equals(eligible))throw conflict();
  jdbc.update("""
   INSERT INTO dg_agent_permission
    (id,org_id,agent_id,datasource_id,tool_name,object_pattern,action,granted_by,expires_at)
   VALUES(?,?,?,?,'list_datasources','*',?,?,?)
   ON CONFLICT(org_id,agent_id,datasource_id,tool_name,object_pattern)
    DO UPDATE SET action=excluded.action,granted_by=excluded.granted_by,
      expires_at=excluded.expires_at,version=dg_agent_permission.version+1
   """,UUID.randomUUID(),u.orgId(),agentId,input.datasourceId(),
      input.action(),u.userId(),input.expiresAt()==null?null:Timestamp.from(input.expiresAt()));
  audit(u,"AGENT_DISCOVERY_PERMISSION",agentId);
  return Map.of("tool","list_datasources","action",input.action());
 }
 @GetMapping("/{agentId}/datasource-permissions")
 public List<Map<String,String>> grants(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID agentId){
  UserPrincipal u=actor(jwt,"agent:permission:write");
  return jdbc.query("""
   SELECT p.datasource_id,p.action FROM dg_agent_permission p
   JOIN dg_agent a ON a.org_id=p.org_id AND a.id=p.agent_id
   WHERE p.org_id=? AND p.agent_id=? AND p.tool_name='list_datasources'
   ORDER BY p.datasource_id
   """,(rs,i)->Map.of("datasourceId",rs.getString("datasource_id"),
     "action",rs.getString("action")),u.orgId(),agentId);
 }
 private void audit(UserPrincipal u,String operation,UUID id){
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,?,'AGENT',?)
   """,UUID.randomUUID(),u.orgId(),u.userId().toString(),operation,id.toString());
 }
 private static ResponseStatusException conflict(){
  return new ResponseStatusException(HttpStatus.CONFLICT,"Identity or permission unavailable");
 }
 private static ResponseStatusException bad(String reason){
  return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);
 }
}
