package io.github.mcpdataguard.standalone;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadSourceHostPolicyTest {
 @Test void allowsExactConfiguredHostsAndWhitespace(){
  assertTrue(ReadSourceHostPolicy.allowed("fe.internal","localhost, fe.internal "));
  assertFalse(ReadSourceHostPolicy.allowed("fe.internal.attacker.com","fe.internal"));
  assertFalse(ReadSourceHostPolicy.allowed("10.2.3.4","*"));
  assertFalse(ReadSourceHostPolicy.allowed("localhost",""));
  assertFalse(ReadSourceHostPolicy.allowed("a/b","a/b"));
 }
}
