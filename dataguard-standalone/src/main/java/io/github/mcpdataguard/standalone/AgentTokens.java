package io.github.mcpdataguard.standalone;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agents")
public class AgentTokens {
 private final JdbcTemplate jdbc;
 private final AuditService audit;
 private static final SecureRandom RNG=new SecureRandom();
 public AgentTokens(JdbcTemplate jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}
 static String digest(String token){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
  catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 static String generate(){
  byte[] b=new byte[32];RNG.nextBytes(b);
  return "dg_"+Base64.getUrlEncoder().withoutPadding().encodeToString(b);
 }
 @GetMapping
 public List<Map<String,Object>> list(Authentication auth){
  return jdbc.queryForList("SELECT t.id,t.label,t.created_at AS createdAt,t.expires_at AS expiresAt,t.revoked_at AS revokedAt FROM app_agent_tokens t JOIN app_users u ON u.id=t.owner_user_id WHERE u.username=? ORDER BY t.created_at DESC LIMIT 100",auth.getName());
 }
 public record NewToken(String label,int validDays){}
 @PostMapping
 public Map<String,Object> create(Authentication auth,@RequestBody NewToken input){
  if(input==null||input.label()==null||input.label().isBlank()||input.label().length()>100||input.validDays()<1||input.validDays()>90)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid label or expiry");
  String owner=jdbc.queryForObject("SELECT id FROM app_users WHERE username=? AND enabled=1 AND must_change_password=0",String.class,auth.getName());
  String token=generate();String id=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO app_agent_tokens(id,owner_user_id,label,token_digest,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(3),INTERVAL ? DAY))",
    id,owner,input.label().strip(),digest(token),input.validDays());
  audit.log(auth.getName(),"AGENT_TOKEN_CREATED","AGENT",id,"SUCCESS","expiresDays="+input.validDays());
  // The secret appears ONLY in this one HTTPS response. Never log, display twice or persist it.
  return Map.of("id",id,"token",token);
 }
 @PostMapping("/{id}/revoke")
 public Map<String,Object> revoke(Authentication auth,@PathVariable UUID id){
  int changed=jdbc.update("UPDATE app_agent_tokens t JOIN app_users u ON u.id=t.owner_user_id SET t.revoked_at=NOW(3) WHERE t.id=? AND u.username=? AND t.revoked_at IS NULL",id.toString(),auth.getName());
  if(changed!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  audit.log(auth.getName(),"AGENT_TOKEN_REVOKED","AGENT",id.toString(),"SUCCESS","immediate");
  return Map.of("revoked",true);
 }
 public String resolveTokenId(String bearer){
  if(bearer==null||!bearer.matches("dg_[a-zA-Z0-9_-]{43}"))return null;
  List<String> ids=jdbc.query("""
    SELECT t.id FROM app_agent_tokens t JOIN app_users u ON u.id=t.owner_user_id
    WHERE t.token_digest=? AND t.revoked_at IS NULL AND t.expires_at>NOW(3)
      AND u.enabled=1 AND u.must_change_password=0
    """,(rs,i)->rs.getString(1),digest(bearer));
  return ids.size()==1?ids.get(0):null;
 }
 public String resolve(String bearer){
  if(bearer==null||!bearer.matches("dg_[a-zA-Z0-9_-]{43}"))return null;
  List<String> users=jdbc.query("""
    SELECT u.username FROM app_agent_tokens t JOIN app_users u ON u.id=t.owner_user_id
    WHERE t.token_digest=? AND t.revoked_at IS NULL AND t.expires_at>NOW(3)
      AND u.enabled=1 AND u.must_change_password=0
    """,(rs,i)->rs.getString(1),digest(bearer));
  return users.size()==1?users.get(0):null;
 }
}
