package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.NativeTokens;
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

@RestController
@RequestMapping("/api/v1/agents")
public class NativeAgentTokensController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public NativeAgentTokensController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 public record Issued(String token,String tokenType,long expiresIn){}
 private UserPrincipal actor(Jwt jwt){
  UserPrincipal actor=users.requireUser(jwt);
  users.requirePermission(actor,"agent:identity:write");
  return actor;
 }
 @PostMapping("/{agentId}/native-token")
 @Transactional
 public Issued create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID agentId){
  UserPrincipal actor=actor(jwt);
  List<String> states=jdbc.query("""
    SELECT status FROM dg_agent WHERE org_id=? AND id=? FOR UPDATE
    """,(rs,i)->rs.getString(1),actor.orgId(),agentId);
  if(states.size()!=1||"REVOKED".equals(states.get(0)))
   throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not available");
  jdbc.update("""
   UPDATE dg_native_agent_token SET revoked_at=now()
   WHERE org_id=? AND agent_id=? AND revoked_at IS NULL
   """,actor.orgId(),agentId);
  String token=NativeTokens.random();
  jdbc.update("""
   INSERT INTO dg_native_agent_token(id,org_id,agent_id,token_digest,expires_at)
   VALUES (?,?,?,?,now()+interval '30 days')
   """,UUID.randomUUID(),actor.orgId(),agentId,NativeTokens.digest(token));
  if("DISABLED".equals(states.get(0)))
   jdbc.update("UPDATE dg_agent SET status='ACTIVE',version=version+1 WHERE org_id=? AND id=?",
      actor.orgId(),agentId);
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,'NATIVE_AGENT_TOKEN_ROTATED','AGENT',?)
   """,UUID.randomUUID(),actor.orgId(),actor.userId().toString(),agentId.toString());
  return new Issued(token,"Bearer",30L*86400);
 }
 @PostMapping("/{agentId}/native-token/revoke")
 @Transactional
 public Map<String,String> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID agentId){
  UserPrincipal actor=actor(jwt);
  Boolean exists=jdbc.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM dg_agent WHERE org_id=? AND id=?)
   """,Boolean.class,actor.orgId(),agentId);
  if(!Boolean.TRUE.equals(exists))
   throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not available");
  int count=jdbc.update("""
   UPDATE dg_native_agent_token SET revoked_at=now()
   WHERE org_id=? AND agent_id=? AND revoked_at IS NULL
   """,actor.orgId(),agentId);
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,'NATIVE_AGENT_TOKEN_REVOKED','AGENT',?)
   """,UUID.randomUUID(),actor.orgId(),actor.userId().toString(),agentId.toString());
  return Map.of("status","REVOKED","revoked",Integer.toString(count));
 }
}
