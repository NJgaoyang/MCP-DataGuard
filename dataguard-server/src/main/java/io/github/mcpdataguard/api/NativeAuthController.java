package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.NativeTokens;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
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
@RequestMapping("/api/v1/auth")
public class NativeAuthController {
 private final JdbcTemplate jdbc;
 private static final String DUMMY=NativeTokens.passwords().encode("dummy-only-for-equal-time");
 public NativeAuthController(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Login(String organization,String username,String password){}
 public record Credentials(String currentPassword,String newPassword){}
 public record AuthResult(String token,String tokenType,long expiresIn,boolean mustChangePassword){}
 private record Account(UUID orgId,UUID userId,String hash,int failures,Instant lock,boolean mustChange){}
 @PostMapping("/login")
 @Transactional(noRollbackFor=ResponseStatusException.class)
 public AuthResult login(@RequestBody Login input){
  if(input==null || input.organization()==null || !input.organization().matches("[a-z][a-z0-9_-]{2,79}")
     || input.username()==null || !input.username().matches("[A-Za-z0-9_.@-]{3,128}")
     || input.password()==null || input.password().length()>256)throw invalid();
  List<Account> rows=jdbc.query("""
    SELECT u.org_id,u.id,u.password_hash,u.failed_login_attempts,u.login_locked_until,
           u.must_change_password
    FROM dg_user u JOIN dg_organization o ON o.id=u.org_id
    WHERE o.code=? AND o.status='ACTIVE' AND u.username=? AND u.status='ACTIVE'
    FOR UPDATE OF u
    """,(rs,i)->new Account(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
     rs.getString(3),rs.getInt(4),
     rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant(),rs.getBoolean(6)),
     input.organization(),input.username());
  Account u=rows.size()==1?rows.get(0):null;
  if(u==null || u.hash()==null){
   NativeTokens.passwords().matches(input.password(),DUMMY);
   throw invalid();
  }
  Instant now=Instant.now();
  if(u.lock()!=null && u.lock().isAfter(now))throw invalid();
  if(!NativeTokens.passwords().matches(input.password(),u.hash())){
   int failures=u.lock()!=null?1:u.failures()+1;
   jdbc.update("""
    UPDATE dg_user SET failed_login_attempts=?,login_locked_until=?,updated_at=now()
     WHERE org_id=? AND id=?
    """,failures,failures>=5?java.sql.Timestamp.from(now.plusSeconds(900)):null,u.orgId(),u.userId());
   throw invalid();
  }
  jdbc.update("""
    UPDATE dg_user SET failed_login_attempts=0,login_locked_until=NULL,last_login_at=now()
    WHERE org_id=? AND id=?
    """,u.orgId(),u.userId());
  String bearer=NativeTokens.random();
  jdbc.update("""
    INSERT INTO dg_native_session(id,org_id,user_id,token_digest,expires_at)
    VALUES (?,?,?,?,now()+interval '8 hours')
    """,UUID.randomUUID(),u.orgId(),u.userId(),NativeTokens.digest(bearer));
  return new AuthResult(bearer,"Bearer",28800,u.mustChange());
 }
 @PostMapping("/password")
 @Transactional
 public Map<String,String> password(@AuthenticationPrincipal Jwt jwt,@RequestBody Credentials input){
  Principal p=principal(jwt);
  if(input==null || input.currentPassword()==null)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid password");
  try{NativeTokens.requireStrongPassword(input.newPassword());}
  catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
  List<String> hashes=jdbc.query("SELECT password_hash FROM dg_user WHERE org_id=? AND id=? FOR UPDATE",
     (rs,i)->rs.getString(1),p.orgId(),p.id());
  if(hashes.size()!=1||hashes.get(0)==null||
     !NativeTokens.passwords().matches(input.currentPassword(),hashes.get(0)))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CURRENT_PASSWORD_INCORRECT");
  if(NativeTokens.passwords().matches(input.newPassword(),hashes.get(0)))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"New password must differ");
  jdbc.update("""
   UPDATE dg_user SET password_hash=?,must_change_password=false,
    failed_login_attempts=0,login_locked_until=NULL,updated_at=now(),version=version+1
   WHERE org_id=? AND id=?
   """,NativeTokens.passwords().encode(input.newPassword()),p.orgId(),p.id());
  jdbc.update("""
    UPDATE dg_native_session SET revoked_at=now()
    WHERE org_id=? AND user_id=? AND revoked_at IS NULL
    """,p.orgId(),p.id());
  return Map.of("status","PASSWORD_CHANGED_RELOGIN_REQUIRED");
 }
 @PostMapping("/logout")
 public Map<String,String> logout(@AuthenticationPrincipal Jwt jwt){
  Principal p=principal(jwt);
  jdbc.update("""
   UPDATE dg_native_session SET revoked_at=now()
    WHERE org_id=? AND user_id=? AND token_digest=?
   """,p.orgId(),p.id(),NativeTokens.digest(jwt.getTokenValue()));
  return Map.of("status","LOGGED_OUT");
 }
 private Principal principal(Jwt jwt){
  if(jwt==null || !NativeTokens.ISSUER.equals(jwt.getIssuer().toString())||
     !jwt.getSubject().startsWith("local-user:"))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Native user required");
  UUID id=UUID.fromString(jwt.getSubject().substring("local-user:".length()));
  List<Principal> rows=jdbc.query("""
   SELECT u.org_id,u.id FROM dg_user u WHERE u.id=? AND u.status='ACTIVE'
   """,(rs,i)->new Principal(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),id);
  if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Native user required");
  return rows.get(0);
 }
 private static ResponseStatusException invalid(){
  return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
 }
 private record Principal(UUID orgId,UUID id){}
}
