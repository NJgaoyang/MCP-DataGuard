package io.github.mcpdataguard.standalone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GuardTest {
 @Test void strongPasswordRules(){
  assertDoesNotThrow(()->Guard.complex("RealStrong_123!"));
  assertThrows(RuntimeException.class,()->Guard.complex("123456"));
  assertThrows(RuntimeException.class,()->Guard.complex("onlylowercase12"));
 }
 @Test void identifiersAndRoles(){
  assertEquals("read_table",Guard.identifier("read_table"));
  assertThrows(RuntimeException.class,()->Guard.identifier("users; DROP TABLE x"));
  assertEquals("APPROVER",Guard.role("APPROVER"));
  assertThrows(RuntimeException.class,()->Guard.role("OUTSIDER"));
 }
}