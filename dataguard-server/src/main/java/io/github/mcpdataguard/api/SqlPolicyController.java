package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Draft-only SQL policies. Rules cannot be published or used to authorize execution. */
@RestController
@RequestMapping("/api/v1/sql-policies")
public class SqlPolicyController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public SqlPolicyController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 public record DraftInput(String code,String name){}
 public record RenameInput(String name,long expectedVersion){}
 private UserPrincipal admin(Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);
  users.requirePermission(u,"identity:admin");
  return u;
 }
 private static String name(String value){
  if(value==null || value.isBlank() || value.length()>200)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid policy name");
  return value.strip();
 }
 @GetMapping
 public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal u=admin(jwt);
  return jdbc.query("""
    SELECT id,code,name,status,latest_version,version
    FROM dg_sql_policy WHERE org_id=?
    ORDER BY created_at DESC,id DESC LIMIT 100
    """,(rs,i)->Map.<String,Object>of(
     "id",rs.getString("id"),"code",rs.getString("code"),
     "name",rs.getString("name"),"status",rs.getString("status"),
     "latestVersion",rs.getInt("latest_version"),"version",rs.getLong("version")),u.orgId());
 }
 @PostMapping
 @ResponseStatus(HttpStatus.CREATED)
 @Transactional
 public Map<String,Object> create(@AuthenticationPrincipal Jwt jwt,@RequestBody DraftInput input){
  UserPrincipal u=admin(jwt);
  if(input==null || input.code()==null || !input.code().matches("[a-z][a-z0-9_-]{2,119}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid policy code");
  String label=name(input.name());
  UUID id=UUID.randomUUID();
  try{
   jdbc.update("""
     INSERT INTO dg_sql_policy(id,org_id,code,name,status,created_by)
     VALUES (?,?,?,?,'DRAFT',?)
     """,id,u.orgId(),input.code(),label,u.userId());
  }catch(DataIntegrityViolationException e){
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Policy code already exists");
  }
  audit(u,"SQL_POLICY_DRAFT_CREATED",id);
  return Map.of("id",id.toString(),"status","DRAFT","version",0);
 }
 @PutMapping("/{policyId}")
 @Transactional
 public Map<String,Object> rename(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID policyId,
                                   @RequestBody RenameInput input){
  UserPrincipal u=admin(jwt);
  if(input==null || input.expectedVersion()<0)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid version");
  String label=name(input.name());
  int rows=jdbc.update("""
   UPDATE dg_sql_policy SET name=?,version=version+1,updated_at=now()
   WHERE org_id=? AND id=? AND status='DRAFT' AND latest_version=0 AND version=?
   """,label,u.orgId(),policyId,input.expectedVersion());
  if(rows!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Draft unavailable or stale");
  audit(u,"SQL_POLICY_DRAFT_RENAMED",policyId);
  return Map.of("id",policyId.toString(),"status","DRAFT","version",input.expectedVersion()+1);
 }
 private void audit(UserPrincipal u,String operation,UUID id){
  jdbc.update("""
    INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
    VALUES (?,?,?,?,'SQL_POLICY',?)
    """,UUID.randomUUID(),u.orgId(),u.userId().toString(),operation,id.toString());
 }
}
