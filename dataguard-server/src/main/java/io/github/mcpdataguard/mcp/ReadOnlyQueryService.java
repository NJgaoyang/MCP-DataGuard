package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import io.github.mcpdataguard.sql.SqlVerdict;
import io.github.mcpdataguard.sql.StrictReadOnlySql;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * All-or-nothing gateway checks for the isolated PostgreSQL executor.
 * Disabled unless the operator explicitly enables read-only execution and
 * separately marks the datasource/identity/masking/audit controls certified.
 */
@Service
public class ReadOnlyQueryService {
 private final JdbcTemplate jdbc;
 private final AgentPrincipalResolver agents;
 private final IsolatedReadOnlyExecutor executor;
 private final boolean enabled;
 public ReadOnlyQueryService(JdbcTemplate jdbc,AgentPrincipalResolver agents,
     IsolatedReadOnlyExecutor executor,
     @Value("${dataguard.readonly.enabled:false}") boolean enabled){
  this.jdbc=jdbc;this.agents=agents;this.executor=executor;this.enabled=enabled;
 }
 private record Source(String host,int port,String db,String type,String provider,String ref,
   int agentRows,long agentBytes,int agentMillis){}
 private static Map<String,Object> deny(String code){
  return Map.of("decision","DENY","code",code,"retryable",false);
 }
 public Map<String,Object> run(String datasourceId,String database,String sql,
    List<Object> parameters,Integer requestedRows,String requestId){
  AgentPrincipal actor=agents.requireActiveAgent();
  if(!enabled)return deny("READ_EXECUTION_DISABLED");
  UUID id;
  try{id=UUID.fromString(datasourceId);}catch(RuntimeException e){return deny("INVALID_DATASOURCE_ID");}
  if(requestedRows!=null&&(requestedRows<1||requestedRows>100))
   return deny("INVALID_ROW_LIMIT");
  if(parameters!=null&&(parameters.size()>1||
       parameters.stream().anyMatch(p->p!=null && !(p instanceof String ||
         p instanceof Number || p instanceof Boolean))))
   return deny("INVALID_PARAMETERS");
  SqlVerdict sqlVerdict=StrictReadOnlySql.analyze("POSTGRESQL",sql,
      parameters==null?0:parameters.size(),100);
  if(!"SELECT".equals(sqlVerdict.operation()))return deny("UNSUPPORTED_SQL_SHAPE");
  String table=StrictReadOnlySql.table(sql);
  Set<String> required=new HashSet<>(List.of(StrictReadOnlySql.columns(sql)));
  String predicate=StrictReadOnlySql.predicateColumn(sql);
  if(predicate!=null)required.add(predicate);
  List<Source> sources=jdbc.query("""
   SELECT d.host,d.port,d.database_name,d.db_type,s.provider,s.secret_ref,
     a.max_rows,a.max_result_bytes,a.max_execution_ms
   FROM dg_datasource d JOIN dg_datasource_secret s
     ON s.org_id=d.org_id AND s.datasource_id=d.id AND s.purpose='READ'
   JOIN dg_agent a ON a.org_id=d.org_id AND a.id=?
   WHERE d.org_id=? AND d.id=? AND d.status='ACTIVE' AND a.status='ACTIVE'
     AND EXISTS(SELECT 1 FROM dg_datasource_capability c
       WHERE c.org_id=d.org_id AND c.datasource_id=d.id
        AND c.capability='READ_ONLY_EXECUTION'
        AND c.support_status='CERTIFIED' AND c.certified_at IS NOT NULL
        AND c.evidence_ref IS NOT NULL)
   """,(rs,i)->new Source(rs.getString(1),rs.getInt(2),rs.getString(3),
      rs.getString(4),rs.getString(5),rs.getString(6),rs.getInt(7),
      rs.getLong(8),rs.getInt(9)),actor.agentId(),actor.orgId(),id);
  if(sources.size()!=1)return deny("DATASOURCE_NOT_CERTIFIED");
  Source source=sources.get(0);
  if(!"POSTGRESQL".equals(source.type())||database==null||!database.equals(source.db()))
   return deny("DATABASE_NOT_CERTIFIED");
  Boolean toolAllowed=jdbc.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM dg_agent_permission p
     WHERE p.org_id=? AND p.agent_id=? AND p.datasource_id=?
       AND p.tool_name='execute_readonly' AND p.action='ALLOW'
       AND p.object_pattern IN ('*',?)
       AND (p.expires_at IS NULL OR p.expires_at>now())
       AND NOT EXISTS(SELECT 1 FROM dg_agent_permission x
         WHERE x.org_id=p.org_id AND x.agent_id=p.agent_id AND x.datasource_id=p.datasource_id
           AND x.tool_name=p.tool_name AND x.action='DENY'
           AND x.object_pattern IN ('*',?)
           AND (x.expires_at IS NULL OR x.expires_at>now())))
   """,Boolean.class,actor.orgId(),actor.agentId(),id,table,table);
  if(!Boolean.TRUE.equals(toolAllowed))return deny("READ_TOOL_NOT_AUTHORIZED");
  List<ColumnGrantPolicy.Grant> grants=jdbc.query("""
    SELECT g.schema_pattern,g.table_pattern,g.column_pattern,g.decision,g.operations
    FROM dg_object_grant g
    WHERE g.org_id=? AND g.agent_id=? AND g.datasource_id=?
      AND g.table_pattern IN ('*',?)
    """,(rs,i)->new ColumnGrantPolicy.Grant(
      rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
      Set.of((String[])rs.getArray(5).getArray())),
      actor.orgId(),actor.agentId(),id,table);
  if(!ColumnGrantPolicy.allows(table,required,grants))
   return deny("COLUMN_NOT_AUTHORIZED");
  Boolean hardDeny=jdbc.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM dg_sql_policy p
     JOIN dg_sql_policy_version v ON v.org_id=p.org_id AND v.policy_id=p.id
       AND v.revision=p.latest_version
     WHERE p.org_id=? AND p.status='ACTIVE'
       AND v.published_at IS NOT NULL AND v.decision='DENY'
       AND v.rule->>'table'=? AND v.rule->>'operation'='SELECT'
       AND v.rule->>'environment'=(
         SELECT d.environment FROM dg_datasource d WHERE d.org_id=? AND d.id=?))
   """,Boolean.class,actor.orgId(),table,actor.orgId(),id);
  if(Boolean.TRUE.equals(hardDeny))return deny("SQL_POLICY_HARD_DENY");
  if(!"ENV".equals(source.provider())||source.ref()==null||
     !source.ref().matches("DG_TARGET_[A-Z0-9_]{3,64}"))
   return deny("READ_SECRET_PROVIDER_NOT_CERTIFIED");
  String username=System.getenv(source.ref()+"_USER");
  String password=System.getenv(source.ref()+"_PASSWORD");
  if(username==null||username.isBlank()||password==null||password.isBlank())
   return deny("READ_CREDENTIALS_UNAVAILABLE");
  int maxRows=Math.min(requestedRows==null?100:requestedRows,Math.min(100,source.agentRows()));
  int maxBytes=(int)Math.min(1048576L,source.agentBytes());
  int timeout=Math.max(1,Math.min(10,source.agentMillis()/1000));
  if(maxRows<1||maxBytes<1)return deny("INVALID_AGENT_BUDGET");
  try{
   IsolatedReadOnlyExecutor.Result result=executor.execute(
     new IsolatedReadOnlyExecutor.Target(source.host(),source.port(),source.db(),username,password),
     new IsolatedReadOnlyExecutor.Request(sql,parameters,required,maxRows,maxBytes,timeout));
   return Map.of("decision","ALLOW","operation","SELECT",
      "columns",result.columns(),"rows",result.rows(),
      "rowCount",result.rows().size(),"returnedBytes",result.returnedBytes(),
      "masking","TEXT_REDACTED","executionEnabled",true);
  }catch(SecurityException|IllegalArgumentException|SQLException e){
   return deny("READ_EXECUTION_REJECTED");
  }
 }
}
