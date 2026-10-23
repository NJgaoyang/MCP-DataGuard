package io.github.mcpdataguard.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fail-closed value masking. HASH is keyed HMAC-SHA256 scoped to org/source,
 * never an unkeyed dictionary-attackable SHA-256. PARTIAL may reveal at most
 * four trailing characters and requires explicit role-scoped policy.
 */
@Component
public class CertifiedMasker {
 private final byte[] key;
 public CertifiedMasker(@Value("${DG_MASK_HMAC_KEY_BASE64:}") String encoded){
  if(encoded==null||encoded.isBlank()){key=null;return;}
  byte[] decoded=Base64.getDecoder().decode(encoded);
  if(decoded.length!=32)throw new IllegalArgumentException("Mask HMAC key must be 32 bytes");
  key=decoded.clone();
 }
 public boolean hashEnabled(){return key!=null;}
 public Object mask(Object value,String algorithm,String scope){
  if(value==null)return null;
  if("NULLIFY".equals(algorithm))return null;
  if("REDACT".equals(algorithm))return "[REDACTED]";
  if("PARTIAL".equals(algorithm)){
   if(!(value instanceof CharSequence))return "[REDACTED]";
   String text=value.toString();
   if(text.length()<8 || text.length()>255 ||
      !text.matches("[a-zA-Z0-9@._-]+"))return "[REDACTED]";
   return "*".repeat(text.length()-4)+text.substring(text.length()-4);
  }
  if("HASH".equals(algorithm)){
   if(key==null||scope==null||scope.isBlank())
    throw new SecurityException("MASK_HMAC_CERTIFICATION_REQUIRED");
   if(!(value instanceof CharSequence || value instanceof Number ||
         value instanceof Boolean))
    return "[REDACTED]";
   String text=value.toString();
   if(text.length()>4096)throw new SecurityException("MASK_VALUE_TOO_LONG");
   try{
    Mac mac=Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key,"HmacSHA256"));
    byte[] digest=mac.doFinal((scope+"\\u0000"+text).getBytes(StandardCharsets.UTF_8));
    return "hmac256:"+HexFormat.of().formatHex(digest);
   }catch(Exception e){throw new SecurityException("MASK_HMAC_FAILED");}
  }
  throw new SecurityException("UNSUPPORTED_MASK_ALGORITHM");
 }
}
