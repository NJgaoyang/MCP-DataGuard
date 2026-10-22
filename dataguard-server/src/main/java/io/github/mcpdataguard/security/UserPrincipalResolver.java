package io.github.mcpdataguard.security;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Resolves an actual platform user; JWT-supplied org/user identifiers are ignored. */
@Service
public class UserPrincipalResolver {
 private final JdbcTemplate jdbc;
 public UserPrincipalResolver(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public UserPrincipal requireUser(Jwt jwt) {
  if(jwt==null || jwt.getIssuer()==null || jwt.getSubject()==null)
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Registered user required");
  String issuer=jwt.getIssuer().toString(), subject=jwt.getSubject();
  String digest=OidcIdentityHash.compute(issuer,subject);
  List<UserPrincipal> principals=jdbc.query("""
   SELECT u.id, u.org_id, u.username, u.display_name, u.oidc_issuer, u.oidc_subject
   FROM dg_user u JOIN dg_organization o ON o.id=u.org_id
   WHERE u.oidc_identity_sha256=? AND u.status='ACTIVE' AND o.status='ACTIVE'
   """,(rs,index)->new UserPrincipal(
    rs.getObject("org_id",UUID.class),rs.getObject("id",UUID.class),
    rs.getString("username"),rs.getString("display_name"),
    rs.getString("oidc_issuer"),rs.getString("oidc_subject")),digest);
  if(principals.size()!=1) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Registered user required");
  UserPrincipal principal=principals.get(0);
  if(!issuer.equals(principal.issuer()) || !subject.equals(principal.subject()))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid principal");
  return principal;
 }
 public void requirePermission(UserPrincipal actor,String permission){
  if(!List.of("datasource:read","agent:read","datasource:write","agent:write","identity:admin","agent:identity:write","agent:permission:write","datasource:secret:write","datasource:test").contains(permission))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Unsupported permission");
  String rule="[" + '"' + permission + '"' + "]";
  List<Integer> rows=jdbc.query("""
   SELECT 1 FROM dg_user_role ur
   JOIN dg_role r ON r.org_id=ur.org_id AND r.id=ur.role_id
   WHERE ur.org_id=? AND ur.user_id=? AND r.permissions @> CAST(? AS jsonb)
   LIMIT 1
   """,(rs,index)->1,actor.orgId(),actor.userId(),rule);
  if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Permission denied");
 }
 public record UserPrincipal(UUID orgId,UUID userId,String username,String displayName,
                             String issuer,String subject) {}
}
