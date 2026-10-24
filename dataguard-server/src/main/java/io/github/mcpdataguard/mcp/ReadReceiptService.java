package io.github.mcpdataguard.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits a read receipt and its audit-chain entry before rows can leave the gateway. */
@Service
public class ReadReceiptService {
 private final JdbcTemplate jdbc;
 private final DeniedSqlAuditChain audit;
 public ReadReceiptService(JdbcTemplate jdbc,DeniedSqlAuditChain audit){
  this.jdbc=jdbc;this.audit=audit;
 }
 @Transactional
 public void record(UUID org,UUID agent,UUID datasource,String database,String sql,
                    int rows,int bytes){
  record(org,agent,datasource,database,sql,rows,bytes,0L);
 }
 /** Persists the sanitized P95 source row within the same audit transaction. */
 @Transactional
 public void record(UUID org,UUID agent,UUID datasource,String database,String sql,
                    int rows,int bytes,long durationMs){
  if(org==null||agent==null||datasource==null||sql==null||
      durationMs<0||durationMs>3600000||
      rows<0||rows>100||bytes<0||bytes>1048576)
   throw new IllegalArgumentException("Invalid read receipt");
  String fingerprint;
  try{fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
       .digest(sql.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  UUID id=UUID.randomUUID();
  int saved=jdbc.update("""
    INSERT INTO dg_sql_request
      (id,org_id,agent_id,datasource_id,tool_name,database_name,operation,
       sql_fingerprint,risk_level,decision,request_status,finished_at)
    SELECT ?,d.org_id,?,d.id,'execute_readonly',?,'SELECT',?,
      'LOW','ALLOW','READ_DONE',now()
    FROM dg_datasource d WHERE d.org_id=? AND d.id=?
    """,id,agent,database,fingerprint,org,datasource);
  if(saved!=1)throw new SecurityException("Audit datasource not authorized");
  audit.appendRead(org,agent,id,fingerprint,rows,bytes);
  // The metric row is committed atomically with the read receipt and hash chain.
  // No SQL text, parameters, raw result or credentials are stored here.
  jdbc.update("""
    INSERT INTO dg_sql_read_summary
      (org_id,request_id,returned_rows,result_bytes,truncated,duration_ms)
    VALUES(?,?,?,?,false,?)
    """,org,id,rows,bytes,durationMs);
  jdbc.update("""
    INSERT INTO dg_agent_usage_daily
     (org_id,agent_id,usage_day,request_count,returned_bytes)
    VALUES (?,?,CURRENT_DATE,1,?)
    ON CONFLICT(org_id,agent_id,usage_day)
     DO UPDATE SET request_count=dg_agent_usage_daily.request_count+1,
       returned_bytes=dg_agent_usage_daily.returned_bytes+excluded.returned_bytes,
       updated_at=now()
    """,org,agent,bytes);
 }
}
