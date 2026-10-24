package io.github.mcpdataguard.standalone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class ReadMaskTest {
 @Test void deniesEveryUnapprovedValue(){
  assertEquals("[REDACTED]",ReadApi.mask("secret",false));
  assertEquals("[REDACTED]",ReadApi.mask(123,false));
  assertEquals("[REDACTED]",ReadApi.mask(new byte[]{1,2},true));
  assertEquals("[REDACTED]",ReadApi.mask(null,true));
 }
 @Test void permitsOnlyClassifiedShortSafeValues(){
  assertEquals("OK",ReadApi.mask("OK",true));
  assertEquals(42,ReadApi.mask(42,true));
  assertEquals(true,ReadApi.mask(true,true));
  assertEquals("[REDACTED]",ReadApi.mask(new java.util.Date(),true));
  assertEquals(256,((String)ReadApi.mask("x".repeat(600),true)).length());
 }
}