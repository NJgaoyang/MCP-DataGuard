package io.github.mcpdataguard.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM for approval payloads. No plaintext SQL or parameters are persisted. */
@Component
public class ApprovalPayloadCrypto {
 public static final String KEY_REF="ENV:DG_APPROVAL_MASTER_KEY_BASE64";
 private final byte[] key;
 private final SecureRandom random=new SecureRandom();
 private final ObjectMapper mapper=new ObjectMapper();
 public ApprovalPayloadCrypto(@Value("${DG_APPROVAL_MASTER_KEY_BASE64:}") String encoded){
  if(encoded==null||encoded.isBlank()){key=null;return;}
  byte[] raw=Base64.getDecoder().decode(encoded);
  if(raw.length!=32)throw new IllegalArgumentException("Approval key must be 32 bytes");
  key=raw.clone();
 }
 public boolean configured(){return key!=null;}
 public Payload seal(String sql,List<Object> parameters,String reason){
  if(key==null)throw new IllegalStateException("Approval encryption unavailable");
  try{
   byte[] raw=sql.getBytes(StandardCharsets.UTF_8);
   java.util.Map<String,Object> payload=new java.util.LinkedHashMap<>();
   payload.put("parameters",parameters==null?List.of():parameters);
   payload.put("reason",reason);
   byte[] params=mapper.writeValueAsBytes(payload);
   byte[] data=ByteBuffer.allocate(4+raw.length+params.length)
     .putInt(raw.length).put(raw).put(params).array();
   Mac mac=Mac.getInstance("HmacSHA256");
   mac.init(new SecretKeySpec(key,"HmacSHA256"));
   String digest=HexFormat.of().formatHex(mac.doFinal(data));
   return new Payload(encrypt(raw),encrypt(params),digest);
  }catch(Exception e){throw new IllegalStateException("Approval payload protection failed",e);}
 }
 private byte[] encrypt(byte[] data)throws Exception{
  byte[] iv=new byte[12];random.nextBytes(iv);
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
  cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
  byte[] ciphertext=cipher.doFinal(data);
  return ByteBuffer.allocate(iv.length+ciphertext.length).put(iv).put(ciphertext).array();
 }
 public record Payload(byte[] encryptedSql,byte[] encryptedParams,String hmac){}
}
