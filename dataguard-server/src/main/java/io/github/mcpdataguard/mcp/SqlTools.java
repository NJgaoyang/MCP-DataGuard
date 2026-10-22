package io.github.mcpdataguard.mcp;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/** Exposed tool contracts only; every SQL/database operation is denied until
 * authenticated Agent ACL, dialect certification and isolated executors exist. */
@Component
public final class SqlTools {
 private static Map<String,Object> deny(String code) {
  return Map.of("code",code,"decision","DENY","retryable",false,
   "message","MCP DataGuard initialization: access denied until safety controls are certified");
 }
 @McpTool(name="list_datasources",description="List the caller's authorized datasources (not enabled)")
 public Map<String,Object> listDatasources(
  @McpToolParam(description="Environment",required=false) String environment,
  @McpToolParam(description="Cursor",required=false) String cursor,
  @McpToolParam(description="Page size",required=false) Integer pageSize){
  return deny("AGENT_DIRECTORY_NOT_READY");
 }
 @McpTool(name="get_schema",description="Inspect caller-authorized schema (not enabled)")
 public Map<String,Object> getSchema(
  @McpToolParam(description="Datasource ID",required=true) String datasourceId,
  @McpToolParam(description="Schema",required=false) String schema,
  @McpToolParam(description="Table",required=false) String table,
  @McpToolParam(description="Cursor",required=false) String cursor,
  @McpToolParam(description="Page size",required=false) Integer pageSize){
  return deny("METADATA_AUTHORIZATION_NOT_READY");
 }
 @McpTool(name="execute_readonly",description="Execute a verified safe query (disabled)")
 public Map<String,Object> executeReadonly(
  @McpToolParam(description="Datasource ID",required=true) String datasourceId,
  @McpToolParam(description="Database name",required=true) String database,
  @McpToolParam(description="SQL text",required=true) String sql,
  @McpToolParam(description="Bind parameters",required=false) List<Object> parameters,
  @McpToolParam(description="Maximum rows",required=false) Integer pageSize,
  @McpToolParam(description="Request reference",required=false) String requestId){
  return deny("READ_EXECUTOR_NOT_CERTIFIED");
 }
 @McpTool(name="check_sql",description="Check a SQL statement without execution (not enabled)")
 public Map<String,Object> checkSql(
  @McpToolParam(description="Datasource ID",required=true) String datasourceId,
  @McpToolParam(description="Database name",required=true) String database,
  @McpToolParam(description="SQL text",required=true) String sql,
  @McpToolParam(description="Bind parameters",required=false) List<Object> parameters){
  // No dialect may be inferred from untrusted input until datasource lookup is authorized.
  return deny("DIALECT_AND_POLICY_NOT_CERTIFIED");
 }
 @McpTool(name="submit_change",description="Submit a SQL change for human approval (disabled)")
 public Map<String,Object> submitChange(
  @McpToolParam(description="Datasource ID",required=true) String datasourceId,
  @McpToolParam(description="Database name",required=true) String database,
  @McpToolParam(description="SQL text",required=true) String sql,
  @McpToolParam(description="Bind parameters",required=false) List<Object> parameters,
  @McpToolParam(description="Reason",required=true) String reason,
  @McpToolParam(description="Idempotency UUID",required=true) String idempotencyKey){
  return deny("APPROVAL_NOT_IMPLEMENTED");
 }
 @McpTool(name="get_change_status",description="Get status of an authorized change (disabled)")
 public Map<String,Object> getChangeStatus(
  @McpToolParam(description="Approval ID",required=true) String approvalId){
  return deny("APPROVAL_AUTHORIZATION_NOT_IMPLEMENTED");
 }
}
