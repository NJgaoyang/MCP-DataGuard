package io.github.mcpdataguard.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Transactionally appends fingerprint-only events to a tenant-scoped SHA-256 audit chain. */
@Service
public final class DeniedSqlAuditChain {
 private static final String STREAM="mcp-denied-sql";
 private static final String GENESIS="0".repeat(64);
 private final JdbcTemplate jdbc;
 public DeniedSqlAuditChain(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public void append(UUID org,UUID agent,UUID request,String fingerprint,String reason){
  appendEvent(org,agent,request,fingerprint,reason,"mcp-denied-sql","SQL_CHECK_DENIED");
 }
 public void appendRead(UUID org,UUID agent,UUID request,String fingerprint,int rows,int bytes){
  if(rows<0||rows>100||bytes<0||bytes>1048576)
   throw new IllegalArgumentException("Invalid read receipt");
  appendEvent(org,agent,request,fingerprint,"ROWS_"+rows+"_BYTES_"+bytes,
    "mcp-read-sql","SQL_READ_DONE");
 }
 private void appendEvent(UUID org,UUID agent,UUID request,String fingerprint,String reason,
                          String streamName,String eventType){
  if(org==null||agent==null||request==null||fingerprint==null||
    !fingerprint.matches("[a-f0-9]{64}")||reason==null||
    !reason.matches("[A-Z0-9_]{1,100}"))
   throw new IllegalArgumentException("Invalid audit evidence");
  jdbc.update("""
    INSERT INTO dg_audit_stream(id,org_id,stream_key,last_seq,last_hash)
    VALUES (?,?,?,0,?) ON CONFLICT(org_id,stream_key) DO NOTHING
    """,UUID.randomUUID(),org,streamName,GENESIS);
  List<Stream> streams=jdbc.query("""
    SELECT id,last_seq,last_hash FROM dg_audit_stream
    WHERE org_id=? AND stream_key=? FOR UPDATE
    """,(rs,i)->new Stream(rs.getObject("id",UUID.class),
       rs.getLong("last_seq"),rs.getString("last_hash")),org,streamName);
  if(streams.size()!=1)throw new IllegalStateException("Audit stream unavailable");
  Stream stream=streams.get(0);
  long seq=Math.addExact(stream.seq(),1);
  String payload="{\"fingerprint\":\""+fingerprint+"\",\"reason\":\""+reason+"\"}";
  String digest=computeEventHash(stream.hash(),seq,request,agent.toString(),eventType,fingerprint,reason);
  jdbc.update("""
    INSERT INTO dg_audit_event(id,org_id,stream_id,seq_no,request_id,actor_type,
      actor_id,event_type,event_data,prev_hash,event_hash)
    VALUES(?,?,?,?,?,'AGENT',?,?,CAST(? AS jsonb),?,?)
    """,UUID.randomUUID(),org,stream.id(),seq,request,agent.toString(),
     eventType,payload,stream.hash(),digest);
  int updated=jdbc.update("""
    UPDATE dg_audit_stream SET last_seq=?,last_hash=?
    WHERE org_id=? AND id=? AND last_seq=?
    """,seq,digest,org,stream.id(),stream.seq());
  if(updated!=1)throw new IllegalStateException("Concurrent audit write");
 }
 public static String computeEventHash(String previous,long sequence,UUID request,
      String actorId,String type,String fingerprint,String reason){
  if(previous==null||!previous.matches("[a-f0-9]{64}") ||
     sequence<1||request==null||actorId==null||type==null ||
     fingerprint==null||!fingerprint.matches("[a-f0-9]{64}") ||
     reason==null||!reason.matches("[A-Z0-9_]{1,100}"))
   throw new IllegalArgumentException("Invalid audit chain component");
  String payload="{\\"fingerprint\\":\\""+fingerprint+"\\",\\"reason\\":\\""+reason+"\\"}";
  return hash(previous+"|"+sequence+"|"+request+"|"+actorId+"|"+type+"|"+payload);
 }
 private static String hash(String message){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
    .digest(message.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 private record Stream(UUID id,long seq,String hash){}
}
