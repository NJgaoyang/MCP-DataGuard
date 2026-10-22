package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.NativeTokens;
import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Passwords are BCrypt-hashed; 256-bit opaque tokens are hashed before storage.
 * All administrative authorization remains in the existing tenant-scoped RBAC tables.
 * Never creates a default password or anonymous provisioning endpoint. */
@RestController
@RequestMapping("/api/v1/auth")
public final class NativeAuthController {
 private static final String DUMMY_BCRYPT=NativeTokens.encoder().encode("dummy-non-login-secret");
 private static final ConcurrentHashMap<String,Window> WINDOWS=new ConcurrentHashMap<>();
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public NativeAuthController(JdbcTemplate jdbc,UserPrincipalResolver users){this.jdbc=jdbc;this.users=users;}
 public record Login(String organization,String username,String password){}
 public record LoginResult(String token,String tokenType,long expiresIn){}
 public record PasswordChange(String currentPassword,String newPassword){}
 private record Principal(UUID orgId,UUID userId,String hash,int failed,Instant lockedUntil){}
 private record Window(long minute,int attempts){}

 @PostMapping("/login")
 @Transactional(noRollbackFor=ResponseStatusException.class)
 public LoginResult login(@RequestBody Login input,HttpServletRequest request){
  if(input==null || input.organization()==null ||
    !input.organization().matches("[a-z][a-z0-9_-]{2,79}") ||
    input.username()==null || !input.username().matches("[A-Za-z0-9_.@-]{3,128}") ||
    input.password()==null || input.password().length()>256){
   throw denied();
  }
  // Best-effort bounded in-memory throttle; DB lockout is authoritative and survives restarts.
  String key=input.organization()+":"+input.username();
  long minute=System.currentTimeMillis()/60000;
  if(WINDOWS.size()>5000)WINDOWS.clear();
  Window w=WINDOWS.compute(key,(k,v)->v==null || v.minute()!=minute
    ? new Window(minute,1):new Window(minute,v.attempts()+1));
  if(w.attempts()>15)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Try again later");
  List<Principal> matches=jdbc.query("""
    SELECT u.org_id,u.id,u.password_hash,u.failed_login_attempts,u.login_locked_until
    FROM dg_user u JOIN dg_organization o ON o.id=u.org_id
    WHERE o.code=? AND o.status='ACTIVE' AND u.username=? AND u.status='ACTIVE'
    FOR UPDATE OF u
    """,(rs,i)->new Principal(rs.getObject("org_id",UUID.class),
      rs.getObject("id",UUID.class),rs.getString("password_hash"),
      rs.getInt("failed_login_attempts"),
      rs.getTimestamp("login_locked_until")==null?null:rs.getTimestamp("login_locked_until").toInstant()),
      input.organization(),input.username());
  Principal p=matches.size()==1?matches.get(0):null;
  if(p==null || p.hash()==null){
   NativeTokens.encoder().matches(input.password(),DUMMY_BCRYPT);
   throw denied();
  }
  Instant now=Instant.now();
  if(p.lockedUntil()!=null && p.lockedUntil().isAfter(now)){
   throw denied();
  }
  if(!NativeTokens.encoder().matches(input.password(),p.hash())){
   int failures=p.lockedUntil()!=null?1:p.failed()+1;
   jdbc.update("""
     UPDATE dg_user SET failed_login_attempts=?,login_locked_until=?,updated_at=now()
     WHERE org_id=? AND id=?
     """,failures,failures>=5?java.sql.Timestamp.from(now.plusSeconds(900)):null,p.orgId(),p.userId());
   // A transaction that rolls back here would undo lockout. Return status AFTER committing.
   throw denied();
  }
  jdbc.update("""
   UPDATE dg_user SET failed_login_attempts=0,login_locked_until=NULL,last_login_at=now()
   WHERE org_id=? AND id=?
   """,p.orgId(),p.userId());
  String token=NativeTokens.newToken();
  jdbc.update("""
   INSERT INTO dg_native_session(id,org_id,user_id,token_digest,expires_at)
   VALUES (?,?,?,?,now()+interval '8 hours')
   """,UUID.randomUUID(),p.orgId(),p.userId(),NativeTokens.sha256(token));
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,'NATIVE_LOGIN','USER',?)
   """,UUID.randomUUID(),p.orgId(),p.userId().toString(),p.userId().toString());
  return new LoginResult(token,"Bearer",8*3600);
 }
 @PostMapping("/logout")
 @Transactional
 public Map<String,String> logout(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal actor=users.requireUser(jwt);
  jdbc.update("""
   UPDATE dg_native_session SET revoked_at=now()
   WHERE org_id=? AND user_id=? AND token_digest=? AND revoked_at IS NULL
   """,actor.orgId(),actor.userId(),NativeTokens.sha256(jwt.getTokenValue()));
  return Map.of("status","LOGGED_OUT");
 }
 @PostMapping("/password")
 @Transactional
 public Map<String,String> changePassword(@AuthenticationPrincipal Jwt jwt,@RequestBody PasswordChange input){
  UserPrincipal actor=users.requireUser(jwt);
  if(input==null || input.currentPassword()==null)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password required");
  try{NativeTokens.validatePassword(input.newPassword());}
  catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
  List<String> passwords=jdbc.query("""
    SELECT password_hash FROM dg_user WHERE org_id=? AND id=? FOR UPDATE
    """,(rs,i)->rs.getString(1),actor.orgId(),actor.userId());
  if(passwords.size()!=1 || passwords.get(0)==null ||
    !NativeTokens.encoder().matches(input.currentPassword(),passwords.get(0)))
   throw denied();
  if(NativeTokens.encoder().matches(input.newPassword(),passwords.get(0)))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"New password must differ");
  jdbc.update("""
   UPDATE dg_user SET password_hash=?,failed_login_attempts=0,
     login_locked_until=NULL,version=version+1,updated_at=now()
   WHERE org_id=? AND id=?
   """,NativeTokens.encoder().encode(input.newPassword()),actor.orgId(),actor.userId());
  jdbc.update("""
    UPDATE dg_native_session SET revoked_at=now()
    WHERE org_id=? AND user_id=? AND revoked_at IS NULL
    """,actor.orgId(),actor.userId());
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,'NATIVE_PASSWORD_CHANGED','USER',?)
   """,UUID.randomUUID(),actor.orgId(),actor.userId().toString(),actor.userId().toString());
  return Map.of("status","PASSWORD_CHANGED_RELOGIN_REQUIRED");
 }
 private static ResponseStatusException denied(){
  return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
 }
}
