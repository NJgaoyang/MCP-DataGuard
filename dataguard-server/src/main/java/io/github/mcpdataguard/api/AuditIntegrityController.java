package io.github.mcpdataguard.api;

import io.github.mcpdataguard.mcp.DeniedSqlAuditChain;
import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-scoped integrity verifier for local hash-chained SQL request evidence.
 * Does not expose SQL, bind parameters, raw event payload or credentials.
 */
@RestController
@RequestMapping("/api/v1/audit-integrity")
public class AuditIntegrityController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public AuditIntegrityController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 private record Stream(UUID id,long lastSeq,String lastHash){}
 private record Event(long seq,UUID requestId,String actor,String type,String fingerprint,
                      String reason,String prev,String digest){}
 @GetMapping
 public Map<String,Object> verify(@AuthenticationPrincipal Jwt jwt,
   @RequestParam(defaultValue="mcp-denied-sql") String stream){
  UserPrincipal actor=users.requireUser(jwt);
  users.requirePermission(actor,"identity:admin");
  if(!List.of("mcp-denied-sql","mcp-read-sql").contains(stream))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported audit stream");
  List<Stream> heads=jdbc.query("""
    SELECT id,last_seq,last_hash FROM dg_audit_stream
    WHERE org_id=? AND stream_key=?
    """,(rs,i)->new Stream(rs.getObject(1,UUID.class),rs.getLong(2),rs.getString(3)),
    actor.orgId(),stream);
  if(heads.size()!=1)return Map.of("verified",false,"stream",stream,"code","STREAM_NOT_FOUND");
  Stream head=heads.get(0);
  if(head.lastSeq()>10000)
   return Map.of("verified",false,"stream",stream,"code","VERIFICATION_WINDOW_EXCEEDED",
     "checked",0);
  List<Event> events=jdbc.query("""
    SELECT seq_no,request_id,actor_id,event_type,
      event_data->>'fingerprint',event_data->>'reason',prev_hash,event_hash
    FROM dg_audit_event WHERE org_id=? AND stream_id=?
    ORDER BY seq_no LIMIT 10001
    """,(rs,i)->new Event(rs.getLong(1),rs.getObject(2,UUID.class),
      rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),
      rs.getString(7),rs.getString(8)),actor.orgId(),head.id());
  String previous="0".repeat(64);
  long expected=0;
  try{
   for(Event event:events){
    if(event.seq()!=++expected || !previous.equals(event.prev()))
     return Map.of("verified",false,"stream",stream,"code","BROKEN_SEQUENCE","checked",expected-1);
    String calculated=DeniedSqlAuditChain.computeEventHash(previous,event.seq(),
      event.requestId(),event.actor(),event.type(),event.fingerprint(),event.reason());
    if(!MessageDigest.isEqual(calculated.getBytes(StandardCharsets.US_ASCII),
      event.digest().getBytes(StandardCharsets.US_ASCII)))
     return Map.of("verified",false,"stream",stream,"code","HASH_MISMATCH","checked",expected-1);
    previous=calculated;
   }
  }catch(RuntimeException e){
   return Map.of("verified",false,"stream",stream,"code","INVALID_EVENT","checked",expected);
  }
  boolean valid=expected==head.lastSeq() && previous.equals(head.lastHash());
  return Map.of("verified",valid,"stream",stream,
    "code",valid?"CHAIN_VERIFIED":"CHAIN_HEAD_MISMATCH","checked",expected);
 }
}
