package io.github.mcpdataguard.security;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Server-derived Agent identity, never trusts a client-supplied org or agent id. */
@Service
public final class AgentPrincipalResolver {
 private final JdbcTemplate jdbc;
 public AgentPrincipalResolver(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public AgentPrincipal requireActiveAgent() {
  Authentication auth=SecurityContextHolder.getContext().getAuthentication();
  if(!(auth instanceof JwtAuthenticationToken token) || !auth.isAuthenticated())
   throw new AccessDeniedException("Registered Agent identity required");
  Jwt jwt=token.getToken();
  String issuer=jwt.getIssuer()==null?null:jwt.getIssuer().toString();
  String subject=jwt.getSubject();
  if(issuer==null || subject==null || jwt.getAudience()==null)
   throw new AccessDeniedException("Incomplete Agent identity");

  if(NativeTokens.ISSUER.equals(issuer)){
   if(!subject.matches("local-agent:[0-9a-f-]{36}") ||
      !jwt.getAudience().contains(NativeTokens.AUDIENCE) ||
      !"native-agent".equals(jwt.getClaimAsString("azp")))
    throw new AccessDeniedException("Native agent token required");
   UUID agentId=UUID.fromString(subject.substring("local-agent:".length()));
   List<AgentPrincipal> rows=jdbc.query("""
    SELECT a.org_id,a.id FROM dg_agent a JOIN dg_organization o ON o.id=a.org_id
    WHERE a.id=? AND a.status='ACTIVE' AND o.status='ACTIVE'
    """,(rs,i)->new AgentPrincipal(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),agentId);
   if(rows.size()!=1)throw new AccessDeniedException("Agent not active");
   return rows.get(0);
  }
  String digest=OidcIdentityHash.compute(issuer,subject);
  String sql="""
   SELECT i.org_id, i.agent_id, i.issuer, i.subject, i.audience, i.client_id
   FROM dg_agent_identity i
   JOIN dg_agent a ON a.org_id=i.org_id AND a.id=i.agent_id
   JOIN dg_organization o ON o.id=i.org_id
   WHERE i.identity_sha256=? AND i.status='ACTIVE' AND i.revoked_at IS NULL
     AND a.status='ACTIVE' AND o.status='ACTIVE'
   """;
  List<AgentIdentityRow> matches=jdbc.query(sql,(rs,row)->new AgentIdentityRow(
   rs.getObject("org_id",UUID.class),rs.getObject("agent_id",UUID.class),
   rs.getString("issuer"),rs.getString("subject"),rs.getString("audience"),rs.getString("client_id")),digest);
  if(matches.size()!=1) throw new AccessDeniedException("Agent not registered or disabled");
  AgentIdentityRow row=matches.get(0);
  if(!issuer.equals(row.issuer()) || !subject.equals(row.subject())
      || !jwt.getAudience().contains(row.audience()) || row.clientId()==null
      || !row.clientId().equals(jwt.getClaimAsString("azp")))
   throw new AccessDeniedException("Agent identity mismatch");
  return new AgentPrincipal(row.orgId(),row.agentId());
 }
 private record AgentIdentityRow(UUID orgId,UUID agentId,String issuer,String subject,String audience,String clientId) {}
}
