package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real target PostgreSQL / TLS acceptance through the actual MCP tool method:
 * Agent identity -> data source scope -> tool ACL -> exact column grants ->
 * syntax and policy -> isolated physical SELECT -> fail-closed masking -> receipt.
 * No production flags, network exposure or live credentials are used.
 */
class AgentReadMcpIntegrationTest {
 @Test void certifiedAgentCanReadOnlyMaskedRowsWithAuditAndRevocation(){
  String url=System.getenv("DG_OUTBOX_E2E_URL");
  Assumptions.assumeTrue("true".equals(System.getenv("DG_READ_JDBC_E2E")));
  Assumptions.assumeTrue(url!=null &&
    url.startsWith("jdbc:postgresql://127.0.0.1:5432/dataguard"));
  Assumptions.assumeTrue("dg_tls_reader".equals(
    System.getenv("DG_TARGET_E2E_READER_USER")));
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(
    url+"?currentSchema=platform","postgres","test-only"));
  UUID org=UUID.randomUUID(),owner=UUID.randomUUID(),
    agent=UUID.randomUUID(),source=UUID.randomUUID();
  db.update("INSERT INTO dg_organization(id,code,name) VALUES(?,?,?)",
    org,"mcp_read_"+org.toString().replace("-",""),"Isolated MCP read");
  db.update("""
    INSERT INTO dg_user(id,org_id,username,display_name)
    VALUES(?,?,?,'MCP Test Owner')
    """,owner,org,"mcpowner");
  db.update("""
    INSERT INTO dg_agent(id,org_id,owner_user_id,code,name,max_rows,
       max_result_bytes,max_execution_ms)
    VALUES(?,?,?,'read_agent','Read Agent',2,1024,7000)
    """,agent,org,owner);
  db.update("""
    INSERT INTO dg_datasource(id,org_id,owner_user_id,code,name,db_type,
      environment,host,port,database_name,status)
    VALUES(?,?,?,?,?,'POSTGRESQL','TEST','localhost',5432,'dataguard','ACTIVE')
    """,source,org,owner,"read_source","Read test");
  db.update("""
    INSERT INTO dg_datasource_secret
      (id,org_id,datasource_id,purpose,provider,secret_ref)
    VALUES (?,?,?,'READ','ENV','DG_TARGET_E2E_READER')
    """,UUID.randomUUID(),org,source);
  db.update("""
    INSERT INTO dg_datasource_capability
      (org_id,datasource_id,capability,support_status,plugin_version,
       certified_server_version,evidence_ref,certified_at)
    VALUES (?,?,'READ_ONLY_EXECUTION','CERTIFIED','isolated-test',
       'PostgreSQL 17','isolated:tls:readonly',now())
    """,org,source);
  UUID permission=UUID.randomUUID();
  db.update("""
    INSERT INTO dg_agent_permission
      (id,org_id,agent_id,datasource_id,tool_name,object_pattern,action,granted_by)
    VALUES (?,?,?,?,'execute_readonly','readonly_probe','ALLOW',?)
    """,permission,org,agent,source,owner);
  for(String column:List.of("id","amount")){
   db.update("""
     INSERT INTO dg_object_grant
       (id,org_id,agent_id,datasource_id,schema_pattern,table_pattern,
         column_pattern,operations,decision)
     VALUES (?,?,?,?,'*','readonly_probe',?,ARRAY['SELECT'],'ALLOW')
     """,UUID.randomUUID(),org,agent,source,column);
  }

  AgentPrincipalResolver identity=mock(AgentPrincipalResolver.class);
  when(identity.requireActiveAgent()).thenReturn(new AgentPrincipal(org,agent));
  DeniedSqlAuditChain chain=new DeniedSqlAuditChain(db);
  ReadReceiptService receipts=new ReadReceiptService(db,chain);
  ReadOnlyQueryService read=new ReadOnlyQueryService(
    db,identity,new IsolatedReadOnlyExecutor("localhost"),receipts,true);
  SqlTools mcp=new SqlTools(
    mock(AuthorizedDatasourceService.class),
    mock(SqlPreflightService.class),identity,
    mock(AuthorizedSchemaService.class),mock(SqlRequestAuditService.class),
    mock(ChangeRequestService.class),read);
  Map<String,Object> response=mcp.executeReadonly(source.toString(),"dataguard",
    "SELECT id,amount FROM readonly_probe LIMIT 2",List.of(),2,"test-read");
  assertEquals("ALLOW",response.get("decision"),response.toString());
  assertEquals(2,response.get("rowCount"));
  assertEquals(List.of(List.of("[REDACTED]","[REDACTED]"),
                       List.of("[REDACTED]","[REDACTED]")),response.get("rows"));
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_sql_request
    WHERE org_id=? AND agent_id=? AND tool_name='execute_readonly'
      AND request_status='READ_DONE'
    """,Long.class,org,agent));
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_audit_event e
    WHERE e.org_id=? AND e.event_type='SQL_READ_DONE'
    """,Long.class,org));
  // Revocation must take effect on the very next MCP call without a restart.
  db.update("UPDATE dg_agent_permission SET action='DENY' WHERE id=?",permission);
  Map<String,Object> denied=mcp.executeReadonly(source.toString(),"dataguard",
    "SELECT id,amount FROM readonly_probe LIMIT 2",List.of(),2,"test-revoked");
  assertEquals("DENY",denied.get("decision"));
  assertEquals("READ_TOOL_NOT_AUTHORIZED",denied.get("code"));
  assertFalse(denied.containsKey("rows"));
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_sql_request
    WHERE org_id=? AND agent_id=? AND request_status='READ_DONE'
    """,Long.class,org,agent));
 }
}
