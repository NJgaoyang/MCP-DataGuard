package io.github.mcpdataguard.mcp;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CertifiedMaskerTest {
 private static CertifiedMasker keyed(){
  byte[] key=new byte[32];
  for(int i=0;i<key.length;i++)key[i]=(byte)(i+3);
  return new CertifiedMasker(Base64.getEncoder().encodeToString(key));
 }
 @Test void keyedDigestsVaryAcrossTenantsAndRejectPlainSha(){
  var mask=keyed();
  String first=(String)mask.mask("account0001","HASH","orgA:source1");
  String other=(String)mask.mask("account0001","HASH","orgB:source1");
  assertTrue(first.startsWith("hmac256:"));
  assertNotEquals(first,other);
  assertEquals(first,mask.mask("account0001","HASH","orgA:source1"));
  assertFalse(first.contains("account0001"));
 }
 @Test void partialRevealsAtMostLastFourFromExplicitText(){
  var mask=keyed();
  assertEquals("****5678",mask.mask("12345678","PARTIAL","orgA"));
  assertEquals("[REDACTED]",mask.mask("1234","PARTIAL","orgA"));
  assertEquals("[REDACTED]",mask.mask(12345678,"PARTIAL","orgA"));
  assertNull(mask.mask(null,"PARTIAL","orgA"));
  assertEquals("[REDACTED]",mask.mask("12345678","REDACT","orgA"));
 }
 @Test void absentKeyNeverFallsBackToUnkeyedHash(){
  var mask=new CertifiedMasker("");
  assertFalse(mask.hashEnabled());
  assertThrows(SecurityException.class,()->
    mask.mask("private","HASH","orgA:source1"));
  assertThrows(SecurityException.class,()->
    keyed().mask("value","HASH",""));
 }
}
