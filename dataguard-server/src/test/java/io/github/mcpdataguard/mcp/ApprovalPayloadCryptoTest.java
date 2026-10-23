package io.github.mcpdataguard.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalPayloadCryptoTest {
 private static ApprovalPayloadCrypto crypto(){
  byte[] key=new byte[32];
  for(int i=0;i<key.length;i++)key[i]=(byte)(i+1);
  return new ApprovalPayloadCrypto(Base64.getEncoder().encodeToString(key));
 }
 @Test void sealsSqlAndReasonWithoutPlaintextAndWithFreshNonces(){
  ApprovalPayloadCrypto service=crypto();
  String sql="UPDATE orders SET status=? WHERE id=?";
  var a=service.seal(sql,List.of("PAID",42),"approved for correction");
  var b=service.seal(sql,List.of("PAID",42),"approved for correction");
  assertEquals(64,a.hmac().length());
  assertEquals(a.hmac(),b.hmac());
  assertFalse(java.util.Arrays.equals(a.encryptedSql(),b.encryptedSql()));
  assertFalse(new String(a.encryptedSql(),StandardCharsets.UTF_8).contains(sql));
  assertFalse(new String(a.encryptedParams(),StandardCharsets.UTF_8).contains("approved"));
  assertNotEquals(a.hmac(),service.seal(sql,List.of("FAILED",42),"approved for correction").hmac());
  assertNotEquals(a.hmac(),service.seal(sql,List.of("PAID",42),"another reason").hmac());
 }
 @Test void failsClosedWithoutEncryptionKey(){
  var crypto=new ApprovalPayloadCrypto("");
  assertFalse(crypto.configured());
  assertThrows(IllegalStateException.class,()->crypto.seal("UPDATE t SET a=?",List.of(1),"reason"));
 }
 @Test void rejectsInvalidEncryptionKeySize(){
  assertThrows(IllegalArgumentException.class,()->
    new ApprovalPayloadCrypto(Base64.getEncoder().encodeToString(new byte[16])));
 }
}
