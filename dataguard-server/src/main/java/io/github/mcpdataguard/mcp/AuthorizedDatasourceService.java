package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Lists only explicitly authorized ACTIVE data sources for a registered Agent.
 * Database secrets, host and connection options are NEVER sent to MCP clients.
 */
@Service
public final class AuthorizedDatasourceService {
 private final JdbcTemplate jdbc;
 private final AgentPrincipalResolver identity;
 public AuthorizedDatasourceService(JdbcTemplate jdbc,AgentPrincipalResolver identity){
  this.jdbc=jdbc;this.identity=identity;
 }
 public Map<String,Object> list(String environment,String cursor,Integer pageSize) {
  // Authenticate before validating user-controlled query parameters.
  AgentPrincipal actor=identity.requireActiveAgent();
  int offset=0;
  if(cursor!=null&&!cursor.isBlank()) {
   if(!cursor.matches("[1-9][0-9]{0,5}"))
    return Map.of("code","INVALID_CURSOR","decision","DENY","retryable",false);
   try {offset=Integer.parseInt(cursor);}catch(NumberFormatException e){
    return Map.of("code","INVALID_CURSOR","decision","DENY","retryable",false);
   }
   if(offset>100000)
    return Map.of("code","INVALID_CURSOR","decision","DENY","retryable",false);
  }
  if(environment!=null&&!List.of("DEV","TEST","STAGING","PROD").contains(environment))
   return Map.of("code","INVALID_ENVIRONMENT","decision","DENY","retryable",false);
  int limit=pageSize==null?50:pageSize;
  if(limit<1||limit>100)
   return Map.of("code","INVALID_PAGE_SIZE","decision","DENY","retryable",false);
  String sql="""
   SELECT DISTINCT d.id, d.code, d.name, d.db_type, d.environment, d.database_name
     FROM dg_datasource d
     JOIN dg_agent_permission p ON p.org_id=d.org_id AND p.datasource_id=d.id
    WHERE d.org_id=? AND d.status='ACTIVE' AND p.agent_id=?
      AND p.tool_name='list_datasources' AND p.action='ALLOW'
      AND p.object_pattern='*'
      AND (p.expires_at IS NULL OR p.expires_at>now())
      AND NOT EXISTS(
        SELECT 1 FROM dg_agent_permission x
         WHERE x.org_id=d.org_id AND x.agent_id=p.agent_id
           AND x.datasource_id=d.id AND x.tool_name='list_datasources'
           AND x.action='DENY' AND (x.expires_at IS NULL OR x.expires_at>now()))
   """;
  List<Object> params=new ArrayList<>(List.of(actor.orgId(),actor.agentId()));
  if(environment!=null){sql+=" AND d.environment=?";params.add(environment);}
  sql+=" ORDER BY d.name, d.id LIMIT ? OFFSET ?";
  params.add(limit+1);
  params.add(offset);
  List<Map<String,Object>> data=jdbc.query(sql,(rs,index)->{
   Map<String,Object> result=new LinkedHashMap<>();
   result.put("id",rs.getString("id"));
   result.put("code",rs.getString("code"));
   result.put("name",rs.getString("name"));
   result.put("databaseType",rs.getString("db_type"));
   result.put("environment",rs.getString("environment"));
   result.put("databaseName",rs.getString("database_name"));
   return result;
  },params.toArray());
  boolean hasMore=data.size()>limit;
  List<Map<String,Object>> page=hasMore?data.subList(0,limit):data;
  return Map.of("items",page,"nextCursor",hasMore?Integer.toString(offset+limit):"",
    "hasMore",hasMore);
 }
}
