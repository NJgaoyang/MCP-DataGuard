package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.NativeTokens;
import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
public class IdentityAdministrationController {
 private static final Set<String> DELEGATABLE=Set.of(
   "agent:read","agent:write","datasource:read","datasource:write");
 private static final Pattern CODE=Pattern.compile("[a-z][a-z0-9_-]{2,63}");
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public IdentityAdministrationController(JdbcTemplate jdbc,UserPrincipalResolver users){this.jdbc=jdbc;this.users=users;}
 public record NewMember(String username,String displayName,String password){}
 public record NewRole(String code,String name,Set<String> permissions){}
 public record ResourceId(UUID id){}
 private UserPrincipal admin(Jwt jwt){
  UserPrincipal actor=users.requireUser(jwt);
  users.requirePermission(actor,"identity:admin");
  return actor;
 }
 @GetMapping("/users")
 public List<Map<String,Object>> members(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal actor=admin(jwt);
  return jdbc.query("SELECT id,username,display_name,status FROM dg_user WHERE org_id=? ORDER BY username LIMIT 100",
   (rs,i)->Map.of("id",rs.getString("id"),"username",rs.getString("username"),
    "displayName",rs.getString("display_name"),"status",rs.getString("status")),actor.orgId());
 }
 @GetMapping("/roles")
 public List<Map<String,Object>> roles(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal actor=admin(jwt);
  return jdbc.query("SELECT id,code,name,permissions,system_role FROM dg_role WHERE org_id=? ORDER BY code LIMIT 100",
   (rs,i)->Map.of("id",rs.getString("id"),"code",rs.getString("code"),
    "name",rs.getString("name"),"permissions",rs.getString("permissions"),
    "systemRole",rs.getBoolean("system_role")),actor.orgId());
 }
 @PostMapping("/users")
 @ResponseStatus(HttpStatus.CREATED)
 @Transactional
 public ResourceId registerUser(@AuthenticationPrincipal Jwt jwt,@RequestBody NewMember input){
  UserPrincipal actor=admin(jwt);
  if(input==null || input.username()==null ||
    !Pattern.matches("[A-Za-z0-9_.@-]{3,128}",input.username()))throw bad();
  try{NativeTokens.requireStrongPassword(input.password());}
  catch(IllegalArgumentException err){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,err.getMessage());}
  String display=ManagementValidators.name(input.displayName());
  UUID id=UUID.randomUUID();
  try{
   jdbc.update("""
    INSERT INTO dg_user(id,org_id,username,display_name,status,password_hash,must_change_password)
    VALUES (?,?,?,?,'ACTIVE',?,true)
    """,id,actor.orgId(),input.username(),display,NativeTokens.passwords().encode(input.password()));
  }catch(DataIntegrityViolationException err){throw conflict();}
  audit(actor,"USER_REGISTERED","USER",id);
  return new ResourceId(id);
 }
 @PostMapping("/roles")
 @ResponseStatus(HttpStatus.CREATED)
 @Transactional
 public ResourceId createRole(@AuthenticationPrincipal Jwt jwt,@RequestBody NewRole input){
  UserPrincipal actor=admin(jwt);
  if(input==null || input.code()==null || !CODE.matcher(input.code()).matches() ||
    input.permissions()==null || !DELEGATABLE.containsAll(input.permissions()))
    throw bad();
  String name=ManagementValidators.name(input.name());
  String json=input.permissions().stream().sorted().map(p->"\""+p+"\"")
     .collect(java.util.stream.Collectors.joining(",","[","]"));
  UUID id=UUID.randomUUID();
  try{
   jdbc.update("""
    INSERT INTO dg_role(id,org_id,code,name,permissions,system_role)
    VALUES (?,?,?,?,CAST(? AS jsonb),false)
    """,id,actor.orgId(),input.code(),name,json);
  }catch(DataIntegrityViolationException e){throw conflict();}
  audit(actor,"ROLE_CREATED","ROLE",id);
  return new ResourceId(id);
 }
 @PostMapping("/users/{userId}/roles/{roleId}")
 @Transactional
 public Map<String,String> assign(@AuthenticationPrincipal Jwt jwt,
                     @PathVariable UUID userId,@PathVariable UUID roleId){
  UserPrincipal actor=admin(jwt);
  int changed=jdbc.update("""
   INSERT INTO dg_user_role(org_id,user_id,role_id,assigned_by)
   SELECT ?,u.id,r.id,? FROM dg_user u
     JOIN dg_role r ON r.org_id=u.org_id
   WHERE u.org_id=? AND u.id=? AND u.status='ACTIVE' AND r.id=? AND r.system_role=false
   ON CONFLICT DO NOTHING
   """,actor.orgId(),actor.userId(),actor.orgId(),userId,roleId);
  if(changed!=1)throw conflict();
  audit(actor,"ROLE_ASSIGNED","USER",userId);
  return Map.of("status","ASSIGNED");
 }
 @DeleteMapping("/users/{userId}/roles/{roleId}")
 @Transactional
 public Map<String,String> unassign(@AuthenticationPrincipal Jwt jwt,
                     @PathVariable UUID userId,@PathVariable UUID roleId){
  UserPrincipal actor=admin(jwt);
  int changed=jdbc.update("""
   DELETE FROM dg_user_role ur USING dg_role r
   WHERE ur.org_id=? AND ur.user_id=? AND ur.role_id=?
      AND r.org_id=ur.org_id AND r.id=ur.role_id AND r.system_role=false
   """,actor.orgId(),userId,roleId);
  if(changed!=1)throw conflict();
  audit(actor,"ROLE_REMOVED","USER",userId);
  return Map.of("status","REMOVED");
 }
 private void audit(UserPrincipal actor,String event,String kind,UUID id){
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,?,?,?)
   """,UUID.randomUUID(),actor.orgId(),actor.userId().toString(),event,kind,id.toString());
 }
 private static ResponseStatusException conflict(){
  return new ResponseStatusException(HttpStatus.CONFLICT,"Object unavailable or duplicate");
 }
 private static ResponseStatusException bad(){
  return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid role or native member");
 }
}
