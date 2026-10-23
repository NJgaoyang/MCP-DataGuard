package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipalResolver;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/** Exposed tool contracts only; every SQL/database operation is denied until
 * authenticated Agent ACL, dialect certification and isolated executors exist. */
@Component
public final class SqlTools {
 private final AuthorizedDatasourceService datasources;
 private final SqlPreflightService preflight;
 private final AgentPrincipalResolver agents;
 private final AuthorizedSchemaService schemas;
 private final SqlRequestAuditService audit;
 private final ChangeRequestService changes;
 private final ReadOnlyQueryService readOnly;
 public SqlTools(AuthorizedDatasourceService datasources,SqlPreflightService preflight,
                 AgentPrincipalResolver agents,AuthorizedSchemaService schemas,SqlRequestAuditService audit,
                 ChangeRequestService changes,ReadOnlyQueryService readOnly){
  this.datasources=datasources;this.preflight=preflight;this.agents=agents;this.schemas=schemas;this.audit=audit;this.changes=changes;this.readOnly=readOnly;
 }

 private static Map<String,Object> deny(String code) {
  return Map.of("code",code,"decision","DENY","retryable",false,
   "message","MCP DataGuard initialization: access denied until safety controls are certified");
 }
 @McpTool(name="list_datasources",description="List the caller's authorized datasources (not enabled)")
 public Map<String,Object> listDatasources(
  @McpToolParam(description="Environment",required=false) String environment,
  @McpToolParam(description="Cursor",required=false) String cursor,
  @McpToolParam(description="Page size",required=false) Integer pageSize){
  return datasources.list(environment,cursor,pageSize);
 }
 @McpTool(name="get_schema",description="List explicitly configured authorized columns (not live database metadata)")
 public Map<String,Object> getSchema(
  @McpToolParam(description="Datasource ID",required=true) String datasourceId,
  @McpToolParam(description="Schema",required=false) String schema,
  @McpToolParam(description="Table",required=false) String table,
  @McpToolParam(description="Cursor",required=false) String cursor,
  @McpToolParam(description="Page size",required=false) Integer pageSize){
  return schemas.get(datasourceId,schema,table,cursor,pageSize);
 }
 @McpTool(name="execute_readonly",description="Run strictly scoped read-only SQL only when certified and explicitly enabled")
 public Map<String,Object> executeReadonly(
  @McpToolParam(description="Datasource ID",required=true) String datasourceId,
  @McpToolParam(description="Database name",required=true) String database,
  @McpToolParam(description="SQL text",required=true) String sql,
  @McpToolParam(description="Bind parameters",required=false) List<Object> parameters,
  @McpToolParam(description="Maximum rows",required=false) Integer pageSize,
  @McpToolParam(description="Request reference",required=false) String requestId){
  return readOnly.run(datasourceId,database,sql,parameters,pageSize,requestId);
 }
 @McpTool(name="check_sql",description="Check a SQL statement without execution (not enabled)")
 public Map<String,Object> checkSql(
  @McpToolParam(description="Datasource ID",required=true) String datasourceId,
  @McpToolParam(description="Database name",required=true) String database,
  @McpToolParam(description="SQL text",required=true) String sql,
  @McpToolParam(description="Bind parameters",required=false) List<Object> parameters){
  Map<String,Object> result=preflight.check(datasourceId,database,sql,parameters);
  audit.recordDeniedCheck(datasourceId,database,sql,result);
  return result;
 }
 @McpTool(name="submit_change",description="Submit an encrypted SQL change for approval (never executes SQL)")
 public Map<String,Object> submitChange(
  @McpToolParam(description="Datasource ID",required=true) String datasourceId,
  @McpToolParam(description="Database name",required=true) String database,
  @McpToolParam(description="SQL text",required=true) String sql,
  @McpToolParam(description="Bind parameters",required=false) List<Object> parameters,
  @McpToolParam(description="Reason",required=true) String reason,
  @McpToolParam(description="Idempotency UUID",required=true) String idempotencyKey){
  return changes.submit(datasourceId,database,sql,parameters,reason,idempotencyKey);
 }
 @McpTool(name="get_change_status",description="Read the caller-owned SQL approval status")
 public Map<String,Object> getChangeStatus(
  @McpToolParam(description="Approval ID",required=true) String approvalId){
  return changes.status(approvalId);
 }
}
