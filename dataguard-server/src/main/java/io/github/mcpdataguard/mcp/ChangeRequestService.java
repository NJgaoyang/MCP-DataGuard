package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persist protected change requests for human review. No SQL execution path.
 * Only one-table parameterized UPDATE / DELETE is accepted for review intake.
 */
@Service
public class ChangeRequestService {
 private static final Pattern UPDATE=Pattern.compile(
  "(?i)^UPDATE\\s+([a-z][a-z0-9_]*)\\s+SET\\s+([a-z][a-z0-9_]*)\\s*=\\s*\\?"+
  "\\s+WHERE\\s+([a-z][a-z0-9_]*)\\s*=\\s*\\?$");
 private static final Pattern DELETE=Pattern.compile(
  "(?i)^DELETE\\s+FROM\\s+([a-z][a-z0-9_]*)\\s+WHERE"+
  "\\s+([a-z][a-z0-9_]*)\\s*=\\s*\\?$");
 private final JdbcTemplate jdbc;
 private final AgentPrincipalResolver agents;
 private final ApprovalPayloadCrypto crypto;
 public ChangeRequestService(JdbcTemplate jdbc,AgentPrincipalResolver agents,ApprovalPayloadCrypto crypto){
  this.jdbc=jdbc;this.agents=agents;this.crypto=crypto;
 }
 private static Map<String,Object> denied(String code){
  return Map.of("decision","DENY","code",code,"executionEnabled",false);
 }
 private record Source(String database,String environment){}
 private record Existing(UUID id,String fingerprint,String hmac,UUID approvalId){}
 private static String sha(String value){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
    .digest(value.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 @Transactional
 public Map<String,Object> submit(String datasourceId,String database,String sql,
                                  List<Object> parameters,String reason,String key){
  AgentPrincipal agent=agents.requireActiveAgent();
  UUID datasource,idempotency;
  try{datasource=UUID.fromString(datasourceId);idempotency=UUID.fromString(key);}
  catch(RuntimeException e){return denied("INVALID_REQUEST_IDENTIFIERS");}
  if(sql==null || sql.length()>8192 || sql.isBlank() ||
    sql.contains(";") || sql.contains("--") || sql.contains("/*") || sql.contains("#") ||
    reason==null || reason.isBlank() || reason.length()>1000 ||
    parameters==null || parameters.size()>2 ||
    parameters.stream().anyMatch(p->p==null ||
      !(p instanceof String || p instanceof Number || p instanceof Boolean) ||
      String.valueOf(p).length()>2048))
   return denied("INVALID_CHANGE_REQUEST");
  String normalized=sql.strip();
  Matcher update=UPDATE.matcher(normalized),remove=DELETE.matcher(normalized);
  String operation,table,column;
  if(update.matches()){
   operation="UPDATE";table=update.group(1).toLowerCase(java.util.Locale.ROOT);
   column=update.group(2).toLowerCase(java.util.Locale.ROOT);
   if(parameters.size()!=2)return denied("BIND_PARAMETERS_REQUIRED");
  }else if(remove.matches()){
   operation="DELETE";table=remove.group(1).toLowerCase(java.util.Locale.ROOT);
   column="*";if(parameters.size()!=1)return denied("BIND_PARAMETERS_REQUIRED");
  }else return denied("UNSUPPORTED_CHANGE_SQL");
  if(!crypto.configured())return denied("APPROVAL_ENCRYPTION_NOT_CONFIGURED");
  List<Source> sources=jdbc.query("""
    SELECT d.database_name,d.environment FROM dg_datasource d
    WHERE d.org_id=? AND d.id=? AND d.status='ACTIVE'
    """,(rs,i)->new Source(rs.getString(1),rs.getString(2)),agent.orgId(),datasource);
  if(sources.size()!=1 || database==null || !database.equals(sources.get(0).database()))
   return denied("DATASOURCE_UNAVAILABLE");
  Boolean authorized=jdbc.queryForObject("""
    SELECT EXISTS(SELECT 1 FROM dg_agent_permission p
     WHERE p.org_id=? AND p.agent_id=? AND p.datasource_id=?
       AND p.tool_name='submit_change' AND p.action='ALLOW'
       AND p.object_pattern IN ('*',?)
       AND (p.expires_at IS NULL OR p.expires_at>now())
       AND NOT EXISTS(SELECT 1 FROM dg_agent_permission x
        WHERE x.org_id=p.org_id AND x.agent_id=p.agent_id AND x.datasource_id=p.datasource_id
         AND x.tool_name='submit_change' AND x.action='DENY'
         AND x.object_pattern IN ('*',?)
         AND (x.expires_at IS NULL OR x.expires_at>now())))
    """,Boolean.class,agent.orgId(),agent.agentId(),datasource,table,table);
  if(!Boolean.TRUE.equals(authorized))return denied("CHANGE_TOOL_NOT_AUTHORIZED");
  Boolean grant=jdbc.queryForObject("""
    SELECT EXISTS(SELECT 1 FROM dg_object_grant g
      WHERE g.org_id=? AND g.agent_id=? AND g.datasource_id=?
        AND g.schema_pattern='*' AND g.table_pattern=?
        AND g.column_pattern=? AND g.decision='ALLOW'
        AND ?=ANY(g.operations)
        AND NOT EXISTS(SELECT 1 FROM dg_object_grant x
          WHERE x.org_id=g.org_id AND x.agent_id=g.agent_id AND x.datasource_id=g.datasource_id
            AND x.schema_pattern='*'
            AND x.table_pattern IN ('*',g.table_pattern)
            AND x.column_pattern IN ('*',g.column_pattern)
            AND ?=ANY(x.operations) AND x.decision='DENY'))
    """,Boolean.class,agent.orgId(),agent.agentId(),datasource,table,column,
      operation,operation);
  if(!Boolean.TRUE.equals(grant))return denied("CHANGE_OBJECT_NOT_AUTHORIZED");
  // A published DENY wins before an approval is even created.
  RuntimeSqlPolicyService.Verdict policy=new RuntimeSqlPolicyService(jdbc)
    .evaluate(agent.orgId(),sources.get(0).environment(),table,operation);
  if(policy.denied())return denied("SQL_POLICY_HARD_DENY");
  List<UUID> bindings=jdbc.query("""
    SELECT b.workflow_version_id FROM dg_workflow_binding b
    JOIN dg_workflow_version v ON v.org_id=b.org_id AND v.id=b.workflow_version_id
    WHERE b.org_id=? AND (b.datasource_id=? OR b.datasource_id IS NULL)
       AND b.operation=? AND b.risk_level='HIGH' AND v.status='PUBLISHED'
    ORDER BY CASE WHEN b.datasource_id=? THEN 0 ELSE 1 END,b.priority,b.id LIMIT 1
    """,(rs,i)->rs.getObject(1,UUID.class),agent.orgId(),datasource,operation,datasource);
  if(bindings.size()!=1)return denied("PUBLISHED_APPROVAL_WORKFLOW_REQUIRED");
  ApprovalPayloadCrypto.Payload payload=crypto.seal(normalized,parameters,reason.strip());
  String fingerprint=sha(normalized);
  List<Existing> old=existing(agent,key);
  if(!old.isEmpty())return replay(old.get(0),fingerprint,payload.hmac());
  UUID requestId=UUID.randomUUID(),approvalId=UUID.randomUUID();
  int inserted=jdbc.update("""
    INSERT INTO dg_sql_request
     (id,org_id,agent_id,datasource_id,tool_name,database_name,operation,sql_fingerprint,
      risk_level,decision,request_status,request_idempotency_key)
    VALUES (?,?,?,?,?,?,?,?,'HIGH','REQUIRE_APPROVAL','PENDING_APPROVAL',?)
    ON CONFLICT DO NOTHING
    """,requestId,agent.orgId(),agent.agentId(),datasource,"submit_change",
      database,operation,fingerprint,key);
  if(inserted!=1){
   old=existing(agent,key);
   return old.size()==1?replay(old.get(0),fingerprint,payload.hmac()):
       denied("IDEMPOTENCY_CONFLICT");
  }
  jdbc.update("""
    INSERT INTO dg_sql_request_payload
      (org_id,request_id,encrypted_sql,encrypted_parameters,
       encryption_key_ref,encryption_algorithm,canonical_payload_hmac)
    VALUES(?,?,?,?,?,'AES-256-GCM',?)
    """,agent.orgId(),requestId,payload.encryptedSql(),payload.encryptedParams(),
      ApprovalPayloadCrypto.KEY_REF,payload.hmac());
  jdbc.update("""
    INSERT INTO dg_sql_approval
      (id,org_id,request_id,workflow_version_id,approval_status,execution_status,expires_at)
    VALUES (?,?,?,?,'PENDING','NOT_SCHEDULED',now()+interval '24 hours')
    """,approvalId,agent.orgId(),requestId,bindings.get(0));
  String eventPayload="{\"approvalId\":\""+approvalId+"\",\"workflowVersionId\":\""+bindings.get(0)+"\"}";
  jdbc.update("""
    INSERT INTO dg_outbox_event
      (id,org_id,aggregate_type,aggregate_id,event_type,payload)
    VALUES (?,?,'SQL_APPROVAL',?,'APPROVAL_REQUESTED',CAST(? AS jsonb))
    """,UUID.randomUUID(),agent.orgId(),approvalId,eventPayload);
  return Map.of("decision","REQUIRE_APPROVAL","approvalId",approvalId.toString(),
    "approvalStatus","PENDING","executionEnabled",false);
 }
 private List<Existing> existing(AgentPrincipal agent,String key){
  return jdbc.query("""
    SELECT r.id,r.sql_fingerprint,p.canonical_payload_hmac,a.id AS approval_id
    FROM dg_sql_request r JOIN dg_sql_request_payload p
     ON p.org_id=r.org_id AND p.request_id=r.id
    JOIN dg_sql_approval a ON a.org_id=r.org_id AND a.request_id=r.id
    WHERE r.org_id=? AND r.agent_id=? AND r.request_idempotency_key=?
    """,(rs,i)->new Existing(rs.getObject("id",UUID.class),
      rs.getString("sql_fingerprint"),rs.getString("canonical_payload_hmac"),
      rs.getObject("approval_id",UUID.class)),agent.orgId(),agent.agentId(),key);
 }
 private static Map<String,Object> replay(Existing old,String fingerprint,String hmac){
  if(!old.fingerprint().equals(fingerprint) ||
     !MessageDigest.isEqual(old.hmac().getBytes(StandardCharsets.US_ASCII),
                            hmac.getBytes(StandardCharsets.US_ASCII)))
   return denied("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD");
  return Map.of("decision","REQUIRE_APPROVAL","approvalId",old.approvalId().toString(),
   "approvalStatus","EXISTING","executionEnabled",false);
 }
 public Map<String,Object> status(String approvalId){
  AgentPrincipal agent=agents.requireActiveAgent();
  UUID id;
  try{id=UUID.fromString(approvalId);}catch(RuntimeException e){return denied("INVALID_APPROVAL_ID");}
  List<Map<String,Object>> rows=jdbc.query("""
    SELECT a.approval_status,a.execution_status,r.request_status
    FROM dg_sql_approval a JOIN dg_sql_request r
      ON r.org_id=a.org_id AND r.id=a.request_id
    WHERE a.org_id=? AND a.id=? AND r.agent_id=?
    """,(rs,i)->Map.of("approvalId",id.toString(),
     "approvalStatus",rs.getString("approval_status"),
     "executionStatus",rs.getString("execution_status"),
     "requestStatus",rs.getString("request_status"),
     "executionEnabled",false),agent.orgId(),id,agent.agentId());
  return rows.size()==1?rows.get(0):denied("APPROVAL_NOT_FOUND");
 }
}
