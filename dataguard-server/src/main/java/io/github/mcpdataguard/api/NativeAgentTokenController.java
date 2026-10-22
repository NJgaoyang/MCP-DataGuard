package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.NativeTokens;
import io.github.mcpdataguard.security.OidcIdentityHash;
import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Local Agent tokens are shown ONCE, and only their SHA-256 digests persist.
 * Native tokens do not authorize SQL execution until the independent SQL
 * firewall and physical read-only credentials are certified. */
@RestController
@RequestMapping("/api/v1/agents")
public class NativeAgentTokenController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public NativeAgentTokenController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 public record TokenIssued(String token,String tokenType,long expiresIn,String status){}
 private UserPrincipal actor(Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);users.requirePermission(u,"agent:identity:write");return u;
 }
 @PostMapping("/{agentId}/native-token")
 @Transactional
 public TokenIssued issue(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID agentId){
  UserPrincipal u=actor(jwt);
  List<String> states=jdbc.query("""
   SELECT status FROM dg_agent WHERE org_id=? AND id=? FOR UPDATE
   """,(rs,i)->rs.getString(1),u.orgId(),agentId);
  if(states.size()!=1 || "REVOKED".equals(states.get(0)))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Agent not available");
  String subject="local-agent:"+agentId;
  String digest=OidcIdentityHash.compute(NativeTokens.ISSUER,subject);
  Integer count=jdbc.queryForObject("""
   SELECT count(*) FROM dg_agent_identity
   WHERE org_id=? AND agent_id=?
   """,Integer.class,u.orgId(),agentId);
  if(count!=null && count==0){
   jdbc.update("""
    INSERT INTO dg_agent_identity
    (id,org_id,agent_id,issuer,subject,audience,identity_sha256,client_id,status)
    VALUES (?,?,?,?,?,?,?,'dataguard-native-agent','ACTIVE')
    """,UUID.randomUUID(),u.orgId(),agentId,NativeTokens.ISSUER,
     subject,NativeTokens.AUDIENCE,digest);
  }else {
   Boolean valid=jdbc.queryForObject("""
    SELECT EXISTS(SELECT 1 FROM dg_agent_identity
     WHERE org_id=? AND agent_id=? AND issuer=? AND subject=?
     AND client_id='dataguard-native-agent' AND status='ACTIVE')
    """,Boolean.class,u.orgId(),agentId,NativeTokens.ISSUER,subject);
   if(!Boolean.TRUE.equals(valid))
    throw new ResponseStatusException(HttpStatus.CONFLICT,"Agent has a different identity provider");
  }
  if("DISABLED".equals(states.get(0))){
   jdbc.update("""
    UPDATE dg_agent SET status='ACTIVE',version=version+1,updated_at=now()
    WHERE org_id=? AND id=?
    """,u.orgId(),agentId);
  }
  // Rotate all previously issued tokens atomically.
  jdbc.update("""
   UPDATE dg_native_agent_token SET revoked_at=now()
   WHERE org_id=? AND agent_id=? AND revoked_at IS NULL
   """,u.orgId(),agentId);
  String token=NativeTokens.newToken();
  jdbc.update("""
   INSERT INTO dg_native_agent_token(id,org_id,agent_id,token_digest,expires_at)
   VALUES (?,?,?,?,now()+interval '90 days')
   """,UUID.randomUUID(),u.orgId(),agentId,NativeTokens.sha256(token));
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,'NATIVE_AGENT_TOKEN_ROTATED','AGENT',?)
   """,UUID.randomUUID(),u.orgId(),u.userId().toString(),agentId.toString());
  return new TokenIssued(token,"Bearer",90L*86400,"ACTIVE");
 }
 @PostMapping("/{agentId}/native-token/revoke")
 @Transactional
 public Map<String,String> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID agentId){
  UserPrincipal u=actor(jwt);
  Boolean known=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM dg_agent WHERE org_id=? AND id=?)",
     Boolean.class,u.orgId(),agentId);
  if(!Boolean.TRUE.equals(known))
    throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found");
  jdbc.update("""
   UPDATE dg_native_agent_token SET revoked_at=now()
   WHERE org_id=? AND agent_id=? AND revoked_at IS NULL
   """,u.orgId(),agentId);
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,'NATIVE_AGENT_TOKEN_REVOKED','AGENT',?)
   """,UUID.randomUUID(),u.orgId(),u.userId().toString(),agentId.toString());
  return Map.of("status","REVOKED");
 }
}
