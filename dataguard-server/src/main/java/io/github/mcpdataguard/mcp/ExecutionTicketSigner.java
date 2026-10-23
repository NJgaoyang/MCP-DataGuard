package io.github.mcpdataguard.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HMAC-SHA256 of immutable approval, tenant, request, SQL and task ID. */
@Component
public class ExecutionTicketSigner {
 private final byte[] secret;
 public ExecutionTicketSigner(@Value("${DG_EXECUTION_SIGNING_KEY_BASE64:}") String encoded){
  if(encoded==null||encoded.isBlank()){secret=null;return;}
  byte[] bytes=Base64.getDecoder().decode(encoded);
  if(bytes.length!=32)throw new IllegalArgumentException("Execution signing key must be 32 bytes");
  secret=bytes.clone();
 }
 public boolean configured(){return secret!=null;}
 public String sign(UUID org,UUID approval,UUID request,String fingerprint,
                    String payloadHmac,UUID task){
  if(secret==null)throw new SecurityException("EXECUTION_SIGNING_UNAVAILABLE");
  if(org==null||approval==null||request==null||task==null||
     fingerprint==null||!fingerprint.matches("[a-f0-9]{64}")||
     payloadHmac==null||!payloadHmac.matches("[a-f0-9]{64}"))
   throw new SecurityException("INVALID_EXECUTION_TICKET");
  try{
   Mac mac=Mac.getInstance("HmacSHA256");
   mac.init(new SecretKeySpec(secret,"HmacSHA256"));
   String bound=org+"|"+approval+"|"+request+"|"+fingerprint+"|"+payloadHmac+"|"+task;
   return HexFormat.of().formatHex(mac.doFinal(bound.getBytes(StandardCharsets.UTF_8)));
  }catch(Exception e){throw new IllegalStateException("Execution ticket signing unavailable");}
 }
 public boolean verify(UUID org,UUID approval,UUID request,String fingerprint,
                        String payloadHmac,UUID task,String digest){
  if(digest==null||!digest.matches("[a-f0-9]{64}"))return false;
  return MessageDigest.isEqual(
      sign(org,approval,request,fingerprint,payloadHmac,task)
        .getBytes(StandardCharsets.US_ASCII),
      digest.getBytes(StandardCharsets.US_ASCII));
 }
}
