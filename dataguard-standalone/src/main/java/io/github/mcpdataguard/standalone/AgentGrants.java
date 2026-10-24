package io.github.mcpdataguard.standalone;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Explicit token-scoped read grants; the owner's approved tables are checked separately. */
@RestController
@RequestMapping("/api/agent-grants")
public final class AgentGrants {
 private final JdbcTemplate jdbc;
 private final AuditService audit;
 public AgentGrants(JdbcTemplate jdbc, AuditService audit){this.jdbc=jdbc;this.audit=audit;}
 public record GrantInput(String tokenId,String sourceId,String tableName,boolean enabled){}

 @PostMapping
 public Map<String,Object> grant(Authentication auth,@RequestBody GrantInput input){
  Guard.requireAdmin(auth);
  if(input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Missing grant");
  String token=parse(input.tokenId()), source=parse(input.sourceId()), table=Guard.identifier(input.tableName());
  String actor=jdbc.queryForObject("SELECT id FROM app_users WHERE username=? AND enabled=1",
      String.class,auth.getName());
  Integer count=jdbc.queryForObject("""
    SELECT COUNT(*) FROM app_agent_tokens t CROSS JOIN data_sources s
    JOIN read_table_allowlist w ON w.source_id=s.id AND w.table_name=?
    WHERE t.id=? AND t.revoked_at IS NULL AND t.expires_at>NOW(3)
      AND s.id=? AND s.enabled=1 AND w.enabled=1
    """,Integer.class,table,token,source);
  if(count==null||count!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,
      "Active token and allowlisted table required");
  jdbc.update("""
    INSERT INTO agent_read_permissions(id,token_id,source_id,table_name,enabled,granted_by)
    VALUES(?,?,?,?,?,?)
    ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),granted_by=VALUES(granted_by)
    """,UUID.randomUUID().toString(),token,source,table,input.enabled(),actor);
  audit.log(auth.getName(),"AGENT_GRANT","AGENT",token+"/"+source+"/"+table,
      "SUCCESS",input.enabled()?"ALLOW":"REVOKE");
  return Map.of("updated",true);
 }
 @GetMapping("/{tokenId}")
 public List<Map<String,Object>> list(Authentication auth,@PathVariable String tokenId){
  Guard.requireAdmin(auth);
  return jdbc.queryForList("""
    SELECT source_id AS sourceId,table_name AS tableName,enabled,created_at AS createdAt
    FROM agent_read_permissions WHERE token_id=? ORDER BY source_id,table_name
    """,parse(tokenId));
 }
 public record ColumnGrantInput(String tokenId,String sourceId,String tableName,String columnName,boolean enabled){}
 @PostMapping("/columns")
 public Map<String,Object> grantColumn(Authentication auth,@RequestBody ColumnGrantInput input){
  Guard.requireAdmin(auth);
  if(input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Missing column grant");
  String token=parse(input.tokenId()), source=parse(input.sourceId());
  String table=Guard.identifier(input.tableName()), column=Guard.identifier(input.columnName());
  if(!allowed(token,source,table))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Agent table grant required");
  Integer approved=jdbc.queryForObject("""
    SELECT COUNT(*) FROM read_column_allowlist
    WHERE source_id=? AND table_name=? AND column_name=? AND enabled=1
    """,Integer.class,source,table,column);
  if(approved==null||approved!=1)
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Owner-level column disclosure must already be allowed");
  String actor=jdbc.queryForObject("SELECT id FROM app_users WHERE username=? AND enabled=1",
      String.class,auth.getName());
  jdbc.update("""
    INSERT INTO agent_column_permissions(id,token_id,source_id,table_name,column_name,enabled,granted_by)
    VALUES(?,?,?,?,?,?,?)
    ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),granted_by=VALUES(granted_by)
    """,UUID.randomUUID().toString(),token,source,table,column,input.enabled(),actor);
  audit.log(auth.getName(),"AGENT_COLUMN_GRANT","AGENT",token+"/"+source+"/"+table,
      "SUCCESS",(input.enabled()?"ALLOW ":"REVOKE ")+column);
  return Map.of("updated",true);
 }
 public java.util.Set<String> allowedColumns(String tokenId,String sourceId,String table){
  if(!allowed(tokenId,sourceId,table))return java.util.Set.of();
  return new java.util.HashSet<>(jdbc.query("""
    SELECT LOWER(c.column_name) FROM agent_column_permissions c
    JOIN read_column_allowlist u ON u.source_id=c.source_id
      AND u.table_name=c.table_name AND LOWER(u.column_name)=LOWER(c.column_name)
    WHERE c.token_id=? AND c.source_id=? AND c.table_name=?
      AND c.enabled=1 AND u.enabled=1
    """,(rs,i)->rs.getString(1),tokenId,sourceId,table));
 }
 public boolean columnAllowed(String tokenId,String sourceId,String table,String column){
  return allowed(tokenId,sourceId,table)&&Boolean.TRUE.equals(jdbc.queryForObject("""
    SELECT EXISTS(SELECT 1 FROM agent_column_permissions
      WHERE token_id=? AND source_id=? AND table_name=? AND LOWER(column_name)=LOWER(?)
      AND enabled=1)
    """,Boolean.class,tokenId,sourceId,table,column));
 }
 private static String parse(String id){
  try{return UUID.fromString(id).toString();}
  catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid UUID");}
 }
 public boolean allowed(String tokenId,String sourceId,String table){
  return Boolean.TRUE.equals(jdbc.queryForObject("""
    SELECT EXISTS(SELECT 1 FROM agent_read_permissions p
      JOIN app_agent_tokens t ON t.id=p.token_id
      WHERE p.token_id=? AND p.source_id=? AND p.table_name=? AND p.enabled=1
      AND t.revoked_at IS NULL AND t.expires_at>NOW(3))
    """,Boolean.class,tokenId,sourceId,table));
 }
}
