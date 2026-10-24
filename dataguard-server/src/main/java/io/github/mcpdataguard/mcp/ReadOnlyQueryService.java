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
 private final ReadReceiptService receipts;
 private final boolean enabled;
 public ReadOnlyQueryService(JdbcTemplate jdbc,AgentPrincipalResolver agents,
     IsolatedReadOnlyExecutor executor,ReadReceiptService receipts,
     @Value("${dataguard.sql.allow-read-execution:false}") boolean enabled){
  this.jdbc=jdbc;this.agents=agents;this.executor=executor;this.receipts=receipts;this.enabled=enabled;
 }
 private record Source(String host,int port,String db,String type,String environment,String provider,String ref,
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
   SELECT d.host,d.port,d.database_name,d.db_type,d.environment,s.provider,s.secret_ref,
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
      rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),
      rs.getInt(8),rs.getLong(9),rs.getInt(10)),actor.agentId(),actor.orgId(),id);
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
  RuntimeSqlPolicyService.Verdict policy=new RuntimeSqlPolicyService(jdbc)
    .evaluate(actor.orgId(),source.environment(),table,"SELECT");
  if(policy.denied())return deny("SQL_POLICY_HARD_DENY");
  if(policy.approvalRequired())return deny("SQL_POLICY_REQUIRES_APPROVAL");
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
  // Fail closed: every unspecified projected column is REDACT.
  // HASH/PARTIAL require a role-scoped rule; HASH also needs a configured
  // HMAC key. No rule ever enables plaintext return.
  Map<String,String> masks=new java.util.HashMap<>();
  List<Map.Entry<String,String>> configured=jdbc.query("""
    SELECT m.column_pattern,m.algorithm FROM dg_mask_rule m
    WHERE m.org_id=? AND m.datasource_id=? AND m.enabled=true
      AND m.schema_pattern IN ('*','public') AND m.table_pattern IN ('*',?)
      AND (m.column_pattern='*' OR m.column_pattern ~ '^[a-z][a-z0-9_]*$')
      AND (m.rule_config->>'roleCode' IS NULL
        OR EXISTS (
         SELECT 1 FROM dg_agent a JOIN dg_user_role ur
           ON ur.org_id=a.org_id AND ur.user_id=a.owner_user_id
         JOIN dg_role role ON role.org_id=ur.org_id AND role.id=ur.role_id
         WHERE a.org_id=m.org_id AND a.id=?
           AND role.code=m.rule_config->>'roleCode'
        ))
      AND (m.algorithm IN ('REDACT','NULLIFY')
        OR m.rule_config->>'roleCode' IS NOT NULL)
    ORDER BY m.priority,
      CASE WHEN m.table_pattern='*' THEN 1 ELSE 0 END,
      CASE WHEN m.column_pattern='*' THEN 1 ELSE 0 END,m.id
    """,(rs,i)->Map.entry(rs.getString(1),rs.getString(2)),
       actor.orgId(),id,table,actor.agentId());
  for(String column:required){
   String selected="REDACT";
   for(Map.Entry<String,String> rule:configured){
    if(rule.getKey().equals("*")||rule.getKey().equals(column)){
     selected=rule.getValue();break;
    }
   }
   if(!List.of("REDACT","NULLIFY","HASH","PARTIAL").contains(selected))
    return deny("MASK_ALGORITHM_NOT_CERTIFIED");
   if("HASH".equals(selected)&&!executor.hashMaskConfigured())
    return deny("MASK_HMAC_KEY_NOT_CONFIGURED");
   masks.put(column,selected);
  }
  final long startedNanos=System.nanoTime();
  try{
   IsolatedReadOnlyExecutor.Result result=executor.execute(
     new IsolatedReadOnlyExecutor.Target(source.host(),source.port(),source.db(),username,password),
     new IsolatedReadOnlyExecutor.Request(sql,parameters,required,maxRows,maxBytes,timeout,masks,
       actor.orgId()+":"+id));
   // A failed control-plane receipt fails closed; never disclose unlogged results.
   receipts.record(actor.orgId(),actor.agentId(),id,database,sql,
     result.rows().size(),result.returnedBytes(),
     Math.max(0L,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
       System.nanoTime()-startedNanos)));
   return Map.of("decision","ALLOW","operation","SELECT",
      "columns",result.columns(),"rows",result.rows(),
      "rowCount",result.rows().size(),"returnedBytes",result.returnedBytes(),
      "masking","TENANT_SCOPED_POLICY","executionEnabled",true);
  }catch(RuntimeException|SQLException e){
   return deny("READ_EXECUTION_REJECTED");
  }
 }
}
