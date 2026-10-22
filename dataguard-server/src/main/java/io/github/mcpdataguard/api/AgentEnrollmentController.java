package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.OidcIdentityHash;
import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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
 private final String issuer,audience;
 public AgentEnrollmentController(JdbcTemplate jdbc,UserPrincipalResolver users,
    @Value("${dataguard.security.issuer}") String issuer,
    @Value("${dataguard.security.audience}") String audience) {
  this.jdbc=jdbc;this.users=users;this.issuer=issuer;this.audience=audience;
 }
 public record IdentityInput(String issuer,String subject,String audience,String clientId){}
 public record PermissionInput(UUID datasourceId,String action,Instant expiresAt){}
 private UserPrincipal actor(Jwt jwt,String permission){
  UserPrincipal u=users.requireUser(jwt);users.requirePermission(u,permission);return u;
 }
 @PostMapping("/{agentId}/enroll")
 @Transactional
 public Map<String,String> enroll(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID agentId,
                                  @RequestBody IdentityInput input){
  UserPrincipal u=actor(jwt,"agent:identity:write");
  if(input==null || !issuer.equals(input.issuer()) || !audience.equals(input.audience()) ||
    input.subject()==null || input.subject().isBlank() || input.subject().length()>512 ||
    input.clientId()==null || input.clientId().isBlank() || input.clientId().length()>255)
   throw bad("OIDC identity invalid");
  List<String> states=jdbc.query("""
   SELECT status FROM dg_agent WHERE org_id=? AND id=? FOR UPDATE
   """,(rs,i)->rs.getString(1),u.orgId(),agentId);
  if(states.size()!=1 || !"DISABLED".equals(states.get(0)))throw conflict();
  Integer bound=jdbc.queryForObject("""
   SELECT count(*) FROM dg_agent_identity WHERE org_id=? AND agent_id=?
   """,Integer.class,u.orgId(),agentId);
  if(bound==null || bound!=0)throw conflict();
  String identityHash=OidcIdentityHash.compute(issuer,input.subject());
  // No user principal may also serve as an Agent.
  Boolean person=jdbc.queryForObject("""
    SELECT EXISTS(SELECT 1 FROM dg_user WHERE oidc_identity_sha256=?)
  """,Boolean.class,identityHash);
  if(Boolean.TRUE.equals(person))throw conflict();
  try{
   jdbc.update("""
    INSERT INTO dg_agent_identity(id,org_id,agent_id,issuer,subject,audience,
      identity_sha256,client_id,status)
    VALUES (?,?,?,?,?,?,?,?,'ACTIVE')
    """,UUID.randomUUID(),u.orgId(),agentId,issuer,input.subject(),
      audience,identityHash,input.clientId());
  }catch(DataIntegrityViolationException e){throw conflict();}
  jdbc.update("""
    UPDATE dg_agent SET status='ACTIVE',version=version+1,updated_at=now()
    WHERE org_id=? AND id=? AND status='DISABLED'
    """,u.orgId(),agentId);
  audit(u,"AGENT_ENROLLED",agentId);
  return Map.of("status","ACTIVE");
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
