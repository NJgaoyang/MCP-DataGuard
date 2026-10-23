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

/** Administer exact SELECT column grants; does not enable physical SQL execution. */
@RestController
@RequestMapping("/api/v1/agents/{agentId}/column-grants")
public class AgentColumnGrantController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public AgentColumnGrantController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 public record GrantInput(UUID datasourceId,String table,String column,String action){}
 private UserPrincipal actor(Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);
  users.requirePermission(u,"agent:permission:write");
  return u;
 }
 private static boolean name(String value){
  return value!=null && value.matches("[a-z][a-z0-9_]{0,254}");
 }
 private static ResponseStatusException invalid(){
  return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Exact table and column required");
 }
 @PostMapping
 @Transactional
 public Map<String,String> save(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID agentId,
                                  @RequestBody GrantInput input){
  UserPrincipal u=actor(jwt);
  if(input==null || input.datasourceId()==null || !name(input.table()) ||
     !name(input.column()) || !("ALLOW".equals(input.action()) || "DENY".equals(input.action())))
   throw invalid();
  Boolean eligible=jdbc.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM dg_agent a JOIN dg_datasource d ON d.org_id=a.org_id
    WHERE a.org_id=? AND a.id=? AND a.status='ACTIVE'
      AND d.id=? AND d.status='ACTIVE')
   """,Boolean.class,u.orgId(),agentId,input.datasourceId());
  if(!Boolean.TRUE.equals(eligible))
   throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent or datasource unavailable");
  // The platform grant table has no natural UNIQUE key. Serialize updates for
  // this agent and datasource with row locks so replace is transactional.
  jdbc.queryForObject("""
    SELECT id FROM dg_agent WHERE org_id=? AND id=? FOR UPDATE
    """,UUID.class,u.orgId(),agentId);
  jdbc.update("""
    DELETE FROM dg_object_grant
     WHERE org_id=? AND agent_id=? AND datasource_id=?
       AND schema_pattern='*' AND table_pattern=? AND column_pattern=?
       AND 'SELECT'=ANY(operations)
    """,u.orgId(),agentId,input.datasourceId(),input.table(),input.column());
  jdbc.update("""
    INSERT INTO dg_object_grant
      (id,org_id,agent_id,datasource_id,schema_pattern,table_pattern,column_pattern,operations,decision)
    VALUES (?,?,?,?,'*',?,?,ARRAY['SELECT']::text[],?)
    """,UUID.randomUUID(),u.orgId(),agentId,input.datasourceId(),
    input.table(),input.column(),input.action());
  jdbc.update("""
    INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
    VALUES (?,?,?,'AGENT_COLUMN_GRANT_UPDATED','AGENT',?)
    """,UUID.randomUUID(),u.orgId(),u.userId().toString(),agentId.toString());
  return Map.of("table",input.table(),"column",input.column(),"action",input.action());
 }
 @GetMapping
 public List<Map<String,String>> list(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID agentId,
                                      @RequestParam UUID datasourceId){
  UserPrincipal u=actor(jwt);
  return jdbc.query("""
    SELECT g.table_pattern,g.column_pattern,g.decision FROM dg_object_grant g
    JOIN dg_agent a ON a.org_id=g.org_id AND a.id=g.agent_id
    JOIN dg_datasource d ON d.org_id=g.org_id AND d.id=g.datasource_id
    WHERE g.org_id=? AND g.agent_id=? AND g.datasource_id=?
      AND 'SELECT'=ANY(g.operations) AND g.column_pattern<>'*'
    ORDER BY g.table_pattern,g.column_pattern,g.decision LIMIT 500
    """,(rs,i)->Map.of("table",rs.getString(1),"column",rs.getString(2),
     "action",rs.getString(3)),u.orgId(),agentId,datasourceId);
 }
}
