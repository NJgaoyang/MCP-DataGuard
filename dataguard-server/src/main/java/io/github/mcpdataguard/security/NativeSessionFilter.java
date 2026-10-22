package io.github.mcpdataguard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Opaque bearer tokens, looked up by hash. The fabricated Jwt only adapts the
 * existing permission resolvers; no caller-supplied claims are trusted. */
@Component
public class NativeSessionFilter extends OncePerRequestFilter {
 private final JdbcTemplate jdbc;
 public NativeSessionFilter(JdbcTemplate jdbc){this.jdbc=jdbc;}

 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
      FilterChain chain) throws ServletException,IOException {
  String auth=request.getHeader("Authorization");
  if(auth!=null && auth.startsWith("Bearer ")){
   String bearer=auth.substring(7);
   if(bearer.matches("dg_[A-Za-z0-9_-]{43}") ){
    String digest=NativeTokens.sha256(bearer);
    List<Identity> users=jdbc.query("""
      SELECT s.user_id,s.created_at,s.expires_at
      FROM dg_native_session s
      JOIN dg_user u ON u.org_id=s.org_id AND u.id=s.user_id
      JOIN dg_organization o ON o.id=u.org_id
      WHERE s.token_digest=? AND s.expires_at>now() AND s.revoked_at IS NULL
        AND u.status='ACTIVE' AND o.status='ACTIVE' AND u.password_hash IS NOT NULL
      """,(rs,i)->new Identity(rs.getObject("user_id",UUID.class),
        rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant()),
      digest);
    if(users.size()==1){
     Identity identity=users.get(0);
     establish(bearer,"local-user:"+identity.id(),"dataguard-native-user",identity);
    } else {
     List<Identity> agents=jdbc.query("""
      SELECT s.agent_id,s.created_at,s.expires_at FROM dg_native_agent_token s
      JOIN dg_agent a ON a.org_id=s.org_id AND a.id=s.agent_id
      JOIN dg_organization o ON o.id=a.org_id
      WHERE s.token_digest=? AND s.expires_at>now() AND s.revoked_at IS NULL
        AND a.status='ACTIVE' AND o.status='ACTIVE'
      """,(rs,i)->new Identity(rs.getObject("agent_id",UUID.class),
         rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant()),digest);
     if(agents.size()==1){
      Identity identity=agents.get(0);
      establish(bearer,"local-agent:"+identity.id(),"dataguard-native-agent",identity);
     }
    }
   }
  }
  try{chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}
 }
 private static void establish(String token,String subject,String client,Identity identity){
  Jwt jwt=Jwt.withTokenValue(token).header("typ","dataguard-opaque")
   .issuer(NativeTokens.ISSUER).subject(subject)
   .audience(List.of(NativeTokens.AUDIENCE)).issuedAt(identity.createdAt())
   .expiresAt(identity.expiresAt()).claim("azp",client).build();
  SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
 }
 private record Identity(UUID id,Instant createdAt,Instant expiresAt){}
}
