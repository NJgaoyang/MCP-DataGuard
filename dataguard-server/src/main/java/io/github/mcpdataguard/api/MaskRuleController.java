package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
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

/**
 * Mandatory, fail-closed result masking configuration. REDACT and NULLIFY are
 * supported; PARTIAL and HASH require separate certification and are rejected.
 */
@RestController
@RequestMapping("/api/v1/mask-rules")
public class MaskRuleController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public MaskRuleController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 public record RuleInput(UUID datasourceId,String table,String column,String algorithm,int priority){}
 private UserPrincipal admin(Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);
  users.requirePermission(u,"identity:admin");
  return u;
 }
 private static boolean identifier(String s){
  return s!=null && s.matches("[a-z][a-z0-9_]{0,254}");
 }
 @GetMapping
 public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt,@RequestParam UUID datasourceId){
  UserPrincipal u=admin(jwt);
  return jdbc.query("""
    SELECT m.id,m.table_pattern,m.column_pattern,m.algorithm,m.priority
    FROM dg_mask_rule m JOIN dg_datasource d
       ON d.org_id=m.org_id AND d.id=m.datasource_id
    WHERE m.org_id=? AND m.datasource_id=? AND m.enabled=true
    ORDER BY m.priority,m.id LIMIT 500
    """,(rs,i)->Map.<String,Object>of("id",rs.getString(1),"table",rs.getString(2),
      "column",rs.getString(3),"algorithm",rs.getString(4),
      "priority",rs.getInt(5)),u.orgId(),datasourceId);
 }
 @PostMapping
 @Transactional
 public Map<String,Object> upsert(@AuthenticationPrincipal Jwt jwt,@RequestBody RuleInput input){
  UserPrincipal u=admin(jwt);
  if(input==null||input.datasourceId()==null||!identifier(input.table())||
      !identifier(input.column())||
      !List.of("REDACT","NULLIFY").contains(input.algorithm())||
      input.priority()<1||input.priority()>10000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsafe masking configuration");
  List<UUID> scoped=jdbc.query("""
    SELECT id FROM dg_datasource WHERE org_id=? AND id=? AND status='ACTIVE' FOR UPDATE
    """,(rs,i)->rs.getObject(1,UUID.class),u.orgId(),input.datasourceId());
  if(scoped.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Datasource unavailable");
  jdbc.update("""
    DELETE FROM dg_mask_rule WHERE org_id=? AND datasource_id=?
      AND schema_pattern='*' AND table_pattern=? AND column_pattern=?
    """,u.orgId(),input.datasourceId(),input.table(),input.column());
  UUID id=UUID.randomUUID();
  jdbc.update("""
    INSERT INTO dg_mask_rule(id,org_id,datasource_id,schema_pattern,
      table_pattern,column_pattern,algorithm,priority,enabled)
    VALUES (?,?,?,'*',?,?,?, ?,true)
    """,id,u.orgId(),input.datasourceId(),input.table(),
      input.column(),input.algorithm(),input.priority());
  audit(u,"MASK_RULE_UPDATED",id);
  return Map.of("id",id.toString(),"algorithm",input.algorithm(),"enabled",true);
 }
 @DeleteMapping("/{id}")
 @Transactional
 public Map<String,Object> disable(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){
  UserPrincipal u=admin(jwt);
  int updated=jdbc.update("""
    UPDATE dg_mask_rule SET enabled=false
    WHERE org_id=? AND id=? AND enabled=true
    """,u.orgId(),id);
  if(updated!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Rule not found");
  audit(u,"MASK_RULE_DISABLED",id);
  return Map.of("id",id.toString(),"enabled",false);
 }
 private void audit(UserPrincipal u,String operation,UUID id){
  jdbc.update("""
    INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
    VALUES (?,?,?,?,'MASK_RULE',?)
    """,UUID.randomUUID(),u.orgId(),u.userId().toString(),operation,id.toString());
 }
}
