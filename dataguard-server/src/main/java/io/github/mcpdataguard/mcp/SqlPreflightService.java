package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import io.github.mcpdataguard.sql.SqlVerdict;
import io.github.mcpdataguard.sql.StrictReadOnlySql;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Authenticated, tenant-scoped SQL preflight only. It never returns ALLOW:
 * SQL AST / physical readonly DB identity / field masking remain unverified.
 */
@Service
public final class SqlPreflightService {
 private final JdbcTemplate jdbc;
 private final AgentPrincipalResolver principals;
 public SqlPreflightService(JdbcTemplate jdbc,AgentPrincipalResolver principals) {
  this.jdbc=jdbc;this.principals=principals;
 }
 public Map<String,Object> check(String datasourceId,String database,String sql,List<Object> parameters) {
  // Authentication ALWAYS precedes resource lookup / shape analysis.
  AgentPrincipal agent=principals.requireActiveAgent();
  UUID id;
  try { id=UUID.fromString(datasourceId); }
  catch(RuntimeException e) {return denied("INVALID_DATASOURCE_ID");}
  if(parameters!=null && (parameters.size()>32 || parameters.stream().anyMatch(x->x!=null &&
      !(x instanceof String || x instanceof Number || x instanceof Boolean))))
   return denied("INVALID_PARAMETERS");
  List<Source> sources=jdbc.query("""
     SELECT d.db_type,d.database_name FROM dg_datasource d
     WHERE d.org_id=? AND d.id=? AND d.status='ACTIVE'
     """,(rs,i)->new Source(rs.getString(1),rs.getString(2)),agent.orgId(),id);
  if(sources.size()!=1)return denied("DATASOURCE_NOT_AVAILABLE");
  Source source=sources.get(0);
  if(database==null || !database.equals(source.databaseName()))return denied("DATABASE_MISMATCH");
  boolean discovery=Boolean.TRUE.equals(jdbc.queryForObject("""
    SELECT EXISTS (SELECT 1 FROM dg_agent_permission p
     WHERE p.org_id=? AND p.agent_id=? AND p.datasource_id=?
      AND p.tool_name='list_datasources' AND p.action='ALLOW'
      AND (p.expires_at IS NULL OR p.expires_at>now())
      AND NOT EXISTS (SELECT 1 FROM dg_agent_permission x
       WHERE x.org_id=p.org_id AND x.agent_id=p.agent_id AND x.datasource_id=p.datasource_id
        AND x.tool_name=p.tool_name AND x.action='DENY'
        AND (x.expires_at IS NULL OR x.expires_at>now())))
    """,Boolean.class,agent.orgId(),agent.agentId(),id));
  if(!discovery)return denied("AGENT_NOT_AUTHORIZED");
  SqlVerdict verdict=StrictReadOnlySql.analyze(source.dialect(),sql,
      parameters==null?0:parameters.size(),100);
  if(!"SELECT".equals(verdict.operation()))return denied(verdict.reason());
  String table=StrictReadOnlySql.table(sql);
  boolean objectGrant=Boolean.TRUE.equals(jdbc.queryForObject("""
    SELECT EXISTS (SELECT 1 FROM dg_object_grant g
     WHERE g.org_id=? AND g.agent_id=? AND g.datasource_id=?
       AND g.schema_pattern='*' AND g.table_pattern=?
       AND g.column_pattern='*' AND 'SELECT'=ANY(g.operations) AND g.decision='ALLOW'
       AND NOT EXISTS (SELECT 1 FROM dg_object_grant x
          WHERE x.org_id=g.org_id AND x.agent_id=g.agent_id AND x.datasource_id=g.datasource_id
            AND x.decision='DENY' AND (x.table_pattern='*' OR x.table_pattern=?)))
    """,Boolean.class,agent.orgId(),agent.agentId(),id,table,table));
  if(!objectGrant)return denied("OBJECT_GRANT_NOT_AVAILABLE");
  // Deliberately deny even if an ACL record exists: syntax regex is not an AST.
  return Map.of("code","AST_AND_EXECUTOR_NOT_CERTIFIED","decision","DENY",
   "retryable",false,"operation","SELECT","object",table,
   "message","Authenticated preflight passed basic checks; SQL execution remains disabled");
 }
 private static Map<String,Object> denied(String code){
  return Map.of("code",code,"decision","DENY","retryable",false);
 }
 private record Source(String dialect,String databaseName){}
}
