package io.github.mcpdataguard.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OidcIdentityHashTest {
 @Test void deterministicAndUnambiguous() {
  String h=OidcIdentityHash.compute("issuer","subject");
  assertEquals(64,h.length());
  assertEquals(h,OidcIdentityHash.compute("issuer","subject"));
  assertNotEquals(OidcIdentityHash.compute("ab","c"),OidcIdentityHash.compute("a","bc"));
  assertNotEquals(h,OidcIdentityHash.compute("issuer","other"));
 }
 @Test void rejectsInvalidPrincipals() {
  assertThrows(IllegalArgumentException.class,()->OidcIdentityHash.compute("", "sub"));
  assertThrows(IllegalArgumentException.class,()->OidcIdentityHash.compute("iss", null));
 }
}
