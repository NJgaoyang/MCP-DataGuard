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

/** Bearer input is an opaque 256-bit secret, not a JWT. Database state owns claims. */
@Component
public class NativeSessionFilter extends OncePerRequestFilter {
 private final JdbcTemplate jdbc;
 public NativeSessionFilter(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,
    FilterChain chain)throws ServletException,IOException {
  String auth=req.getHeader("Authorization");
  try {
   if(auth!=null && auth.matches("Bearer dg_[A-Za-z0-9_-]{43}")){
    String raw=auth.substring(7),hash=NativeTokens.digest(raw);
    List<Identity> users=jdbc.query("""
      SELECT u.id,s.created_at,s.expires_at,u.must_change_password
      FROM dg_native_session s JOIN dg_user u ON u.org_id=s.org_id AND u.id=s.user_id
      JOIN dg_organization o ON o.id=u.org_id
      WHERE s.token_digest=? AND s.revoked_at IS NULL AND s.expires_at>now()
       AND u.status='ACTIVE' AND o.status='ACTIVE' AND u.password_hash IS NOT NULL
      """,(rs,i)->new Identity(rs.getObject(1,UUID.class),
        rs.getTimestamp(2).toInstant(),rs.getTimestamp(3).toInstant(),rs.getBoolean(4)),hash);
    if(users.size()==1){
     Identity user=users.get(0);
     // Enforced on the backend. No business access until password rotation.
     if(NativeAccessPolicy.permitted(user.mustChange(),req.getRequestURI()))
      establish(raw,"local-user:"+user.id(),"native-web",user);
    }else{
     List<Identity> agents=jdbc.query("""
      SELECT a.id,t.created_at,t.expires_at FROM dg_native_agent_token t
       JOIN dg_agent a ON a.org_id=t.org_id AND a.id=t.agent_id
       JOIN dg_organization o ON o.id=a.org_id
      WHERE t.token_digest=? AND t.revoked_at IS NULL AND t.expires_at>now()
       AND a.status='ACTIVE' AND o.status='ACTIVE'
      """,(rs,i)->new Identity(rs.getObject(1,UUID.class),
        rs.getTimestamp(2).toInstant(),rs.getTimestamp(3).toInstant(),false),hash);
     if(agents.size()==1)establish(raw,"local-agent:"+agents.get(0).id(),
       "native-agent",agents.get(0));
    }
   }
   chain.doFilter(req,res);
  }finally{SecurityContextHolder.clearContext();}
 }
 private static void establish(String raw,String sub,String client,Identity identity){
  Jwt jwt=Jwt.withTokenValue(raw).header("typ","opaque").issuer(NativeTokens.ISSUER)
   .subject(sub).audience(List.of(NativeTokens.AUDIENCE))
   .issuedAt(identity.created()).expiresAt(identity.expires())
   .claim("azp",client).build();
  SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
 }
 private record Identity(UUID id,Instant created,Instant expires,boolean mustChange){}
}
