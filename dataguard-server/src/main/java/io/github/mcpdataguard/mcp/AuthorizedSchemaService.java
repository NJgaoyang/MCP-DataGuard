package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Returns configured, explicitly granted column names, NOT physical database metadata.
 * This control-plane-only view never opens a target JDBC connection.
 */
@Service
public final class AuthorizedSchemaService {
 private final JdbcTemplate jdbc;
 private final AgentPrincipalResolver agents;
 public AuthorizedSchemaService(JdbcTemplate jdbc,AgentPrincipalResolver agents){
  this.jdbc=jdbc;this.agents=agents;
 }
 private static Map<String,Object> deny(String code){
  return Map.of("decision","DENY","code",code,"retryable",false);
 }
 public Map<String,Object> get(String datasourceId,String schema,String table,String cursor,Integer pageSize){
  AgentPrincipal actor=agents.requireActiveAgent();
  UUID id;
  try{id=UUID.fromString(datasourceId);}catch(RuntimeException e){return deny("INVALID_DATASOURCE_ID");}
  // Physical schemas are not certified yet. Only explicitly scoped configured grants are shown.
  if(table==null || !table.matches("[a-z][a-z0-9_]{0,254}") ||
     (schema!=null && !schema.isBlank() && !schema.matches("[a-z][a-z0-9_]{0,254}")))
   return deny("EXACT_TABLE_REQUIRED");
  int limit=pageSize==null?50:pageSize;
  if(limit<1||limit>100)return deny("INVALID_PAGE_SIZE");
  int offset=0;
  if(cursor!=null && !cursor.isBlank()){
   if(!cursor.matches("[1-9][0-9]{0,5}"))return deny("INVALID_CURSOR");
   try{offset=Integer.parseInt(cursor);}catch(NumberFormatException e){return deny("INVALID_CURSOR");}
   if(offset>100000)return deny("INVALID_CURSOR");
  }
  Boolean permitted=jdbc.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM dg_datasource d
     WHERE d.org_id=? AND d.id=? AND d.status='ACTIVE'
       AND EXISTS(SELECT 1 FROM dg_agent_permission p
         WHERE p.org_id=d.org_id AND p.datasource_id=d.id AND p.agent_id=?
           AND p.tool_name='get_schema' AND p.action='ALLOW'
           AND (p.expires_at IS NULL OR p.expires_at>now())
           AND (p.object_pattern='*' OR p.object_pattern=?))
       AND NOT EXISTS(SELECT 1 FROM dg_agent_permission x
         WHERE x.org_id=d.org_id AND x.datasource_id=d.id AND x.agent_id=?
           AND x.tool_name='get_schema' AND x.action='DENY'
           AND (x.expires_at IS NULL OR x.expires_at>now())
           AND (x.object_pattern='*' OR x.object_pattern=?)))
   """,Boolean.class,actor.orgId(),id,actor.agentId(),table,actor.agentId(),table);
  if(!Boolean.TRUE.equals(permitted))return deny("METADATA_NOT_AUTHORIZED");
  // A matching explicit or wildcard DENY takes precedence; wildcard ALLOW never
  // reveals new fields. Only exact, configured SELECT column grants are returned.
  List<String> columns=jdbc.query("""
    SELECT DISTINCT g.column_pattern FROM dg_object_grant g
    WHERE g.org_id=? AND g.agent_id=? AND g.datasource_id=?
      AND g.table_pattern=? AND g.column_pattern<>'*' AND 'SELECT'=ANY(g.operations)
      AND g.decision='ALLOW'
      AND (g.schema_pattern='*' OR g.schema_pattern=?)
      AND NOT EXISTS(SELECT 1 FROM dg_object_grant x
        WHERE x.org_id=g.org_id AND x.agent_id=g.agent_id AND x.datasource_id=g.datasource_id
          AND (x.table_pattern='*' OR x.table_pattern=g.table_pattern)
          AND (x.schema_pattern='*' OR x.schema_pattern=?)
          AND (x.column_pattern='*' OR x.column_pattern=g.column_pattern)
          AND 'SELECT'=ANY(x.operations) AND x.decision='DENY')
    ORDER BY g.column_pattern LIMIT ? OFFSET ?
    """,(rs,i)->rs.getString(1),actor.orgId(),actor.agentId(),id,table,
       schema==null?"":schema,schema==null?"":schema,limit+1,offset);
  boolean more=columns.size()>limit;
  return Map.of("decision","ALLOW","metadataSource","CONFIGURED_GRANTS_ONLY",
   "physicalSchemaVerified",false,"table",table,
   "columns",more?columns.subList(0,limit):columns,
   "hasMore",more,"nextCursor",more?Integer.toString(offset+limit):"");
 }
}
