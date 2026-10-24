package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records non-sensitive metadata of denied check_sql calls; never stores SQL text,
 * bound parameters or database credentials. Missing resources are not materialized.
 */
@Service
public class SqlRequestAuditService {
 private final JdbcTemplate jdbc;
 private final AgentPrincipalResolver agents;
 private final DeniedSqlAuditChain chain;
 private final DeniedSqlAlertService alerts;
 public SqlRequestAuditService(JdbcTemplate jdbc,AgentPrincipalResolver agents,
    DeniedSqlAuditChain chain,DeniedSqlAlertService alerts){
  this.jdbc=jdbc;this.agents=agents;this.chain=chain;this.alerts=alerts;
 }
 @Transactional
 public void recordDeniedCheck(String datasourceId,String database,String sql,Map<String,Object> verdict){
  AgentPrincipal a=agents.requireActiveAgent();
  UUID datasource;
  try{datasource=UUID.fromString(datasourceId);}catch(RuntimeException ignored){return;}
  if(sql==null || sql.length()>100000)return;
  String fingerprint;
  try{
   byte[] digest=MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
   fingerprint=HexFormat.of().formatHex(digest);
  }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  // Tenant scoped INSERT SELECT ensures the foreign key can only reference a
  // datasource actually owned by this Agent's organization.
  UUID requestId=UUID.randomUUID();
  int inserted=jdbc.update("""
    INSERT INTO dg_sql_request(id,org_id,agent_id,datasource_id,tool_name,
       database_name,sql_fingerprint,decision,request_status,finished_at)
    SELECT ?,d.org_id,?,?, 'check_sql', ?,?,'DENY','DENIED',now()
    FROM dg_datasource d WHERE d.org_id=? AND d.id=?
    """,requestId,a.agentId(),datasource,database,fingerprint,a.orgId(),datasource);
  if(inserted==1){
   String reason=String.valueOf(verdict.getOrDefault("code","UNKNOWN"));
   if(!reason.matches("[A-Z0-9_]{1,100}"))reason="UNKNOWN";
   chain.append(a.orgId(),a.agentId(),requestId,fingerprint,reason);
   alerts.record(a.orgId(),datasource,reason);
  }
 }
}
