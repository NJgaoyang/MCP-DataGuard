package io.github.mcpdataguard.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeTokensTest {
 @Test void randomTokensAreUniqueAndSha256Stored(){
  String a=NativeTokens.newToken(),b=NativeTokens.newToken();
  assertTrue(a.matches("dg_[A-Za-z0-9_-]{43}"));
  assertNotEquals(a,b);
  assertEquals(64,NativeTokens.sha256(a).length());
  assertNotEquals(NativeTokens.sha256(a),NativeTokens.sha256(b));
 }
 @Test void passwordPolicyUsesBcrypt(){
  String pw="A-long-unique-password-for-admin";
  NativeTokens.validatePassword(pw);
  String hash=NativeTokens.encoder().encode(pw);
  assertTrue(hash.startsWith("$2"));
  assertTrue(NativeTokens.encoder().matches(pw,hash));
  assertFalse(NativeTokens.encoder().matches(pw+"wrong",hash));
  assertThrows(IllegalArgumentException.class,()->NativeTokens.validatePassword("short"));
  assertThrows(IllegalArgumentException.class,()->NativeTokens.validatePassword("x".repeat(73)));
 }
 @Test void stableIdentityIssuer(){
  assertEquals("urn:mcp-dataguard:local",NativeTokens.ISSUER);
  assertEquals("mcp-dataguard",NativeTokens.AUDIENCE);
 }
}
