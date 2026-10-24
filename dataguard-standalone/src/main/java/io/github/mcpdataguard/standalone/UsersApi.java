package io.github.mcpdataguard.standalone;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class UsersApi {
 private final JdbcTemplate jdbc;
 private final PasswordEncoder encoder;
 private final AuditService audit;
 public UsersApi(JdbcTemplate jdbc,PasswordEncoder encoder,AuditService audit){
  this.jdbc=jdbc;this.encoder=encoder;this.audit=audit;
 }
 @GetMapping("/me")
 public Map<String,Object> me(Authentication auth){
  if(auth==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  return jdbc.queryForMap("SELECT id,username,display_name AS displayName,role_code AS roleCode,must_change_password AS mustChangePassword FROM app_users WHERE username=? AND enabled=1",auth.getName());
 }
 public record PasswordInput(String currentPassword,String newPassword){}
 @PostMapping("/me/password")
 public Map<String,Object> changePassword(Authentication auth,@RequestBody PasswordInput input,HttpServletRequest req){
  if(auth==null||input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  Guard.complex(input.newPassword());
  String current=jdbc.queryForObject("SELECT password_hash FROM app_users WHERE username=?",String.class,auth.getName());
  if(!encoder.matches(input.currentPassword(),current)||encoder.matches(input.newPassword(),current))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Incorrect existing password or password reused");
  jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=0,failed_login_count=0,lock_until=NULL WHERE username=?",
    encoder.encode(input.newPassword()),auth.getName());
  audit.log(auth.getName(),"PASSWORD_CHANGED","USER",auth.getName(),"SUCCESS","session revoked");
  if(req.getSession(false)!=null)req.getSession(false).invalidate();
  return Map.of("success",true,"reauthenticate",true);
 }
 @GetMapping("/users")
 public List<Map<String,Object>> users(Authentication auth){
  Guard.requireAdmin(auth);
  return jdbc.queryForList("SELECT id,username,display_name AS displayName,role_code AS roleCode,enabled,must_change_password AS mustChangePassword FROM app_users ORDER BY username LIMIT 500");
 }
 @GetMapping("/users/approvers")
 public List<Map<String,Object>> approvers(){
  return jdbc.queryForList("SELECT id,username,display_name AS displayName FROM app_users WHERE enabled=1 AND role_code IN ('APPROVER','ADMIN') AND must_change_password=0 ORDER BY display_name,username LIMIT 300");
 }
 public record NewUser(String username,String displayName,String roleCode,String temporaryPassword){}
 @PostMapping("/users")
 public Map<String,Object> add(Authentication auth,@RequestBody NewUser input){
  Guard.requireAdmin(auth);
  if(input==null||input.displayName()==null||input.displayName().isBlank()||input.displayName().length()>100)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid user");
  String username=Guard.username(input.username());
  String role=Guard.role(input.roleCode());
  Guard.complex(input.temporaryPassword());
  UUID id=UUID.randomUUID();
  jdbc.update("INSERT INTO app_users(id,username,display_name,password_hash,role_code,must_change_password) VALUES(?,?,?,?,?,1)",
    id.toString(),username,input.displayName().strip(),encoder.encode(input.temporaryPassword()),role);
  audit.log(auth.getName(),"USER_CREATED","USER",id.toString(),"SUCCESS","role="+role);
  return Map.of("id",id.toString(),"username",username,"mustChangePassword",true);
 }
 @PostMapping("/users/{id}/disable")
 public Map<String,Object> disable(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  String own=jdbc.queryForObject("SELECT id FROM app_users WHERE username=?",String.class,auth.getName());
  if(id.toString().equals(own))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cannot disable yourself");
  int n=jdbc.update("UPDATE app_users SET enabled=0 WHERE id=? AND username<>'admin'",id.toString());
  if(n!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  audit.log(auth.getName(),"USER_DISABLED","USER",id.toString(),"SUCCESS","revoked");
  return Map.of("disabled",true);
 }
 @PostMapping("/users/{id}/enable")
 public Map<String,Object> enable(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  int n=jdbc.update("UPDATE app_users SET enabled=1 WHERE id=?",id.toString());
  if(n!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  audit.log(auth.getName(),"USER_ENABLED","USER",id.toString(),"SUCCESS","");
  return Map.of("enabled",true);
 }
 public record PasswordReset(String temporaryPassword){}
 @PostMapping("/users/{id}/reset-password")
 public Map<String,Object> resetPassword(Authentication auth,@PathVariable UUID id,@RequestBody PasswordReset input){
  Guard.requireAdmin(auth);
  if(input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Temporary password required");
  Guard.complex(input.temporaryPassword());
  int n=jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=1,failed_login_count=0,lock_until=NULL WHERE id=?",
    encoder.encode(input.temporaryPassword()),id.toString());
  if(n!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  audit.log(auth.getName(),"PASSWORD_RESET","USER",id.toString(),"SUCCESS","force password change");
  return Map.of("mustChangePassword",true);
 }
}
