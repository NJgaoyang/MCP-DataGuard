package io.github.mcpdataguard.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeTokensTest {
 @Test void tokensAreUnpredictableAndOnlyDigestStored(){
  String token=NativeTokens.random();
  assertTrue(token.matches("dg_[A-Za-z0-9_-]{43}"));
  assertNotEquals(token,NativeTokens.random());
  assertEquals(64,NativeTokens.digest(token).length());
  assertNotEquals(NativeTokens.digest(token),NativeTokens.digest(NativeTokens.random()));
 }
 @Test void rejectsWeakPasswordsAndHashesStrongPasswords(){
  for(String p:new String[]{"123456","password","password123","OnlyLettersHere",
    "NoSpecial12345","no_uppercase_12345!"}){
   assertThrows(IllegalArgumentException.class,()->NativeTokens.requireStrongPassword(p),p);
  }
  String good="Valid!Password2026";
  NativeTokens.requireStrongPassword(good);
  String hash=NativeTokens.passwords().encode(good);
  assertTrue(NativeTokens.passwords().matches(good,hash));
  assertFalse(NativeTokens.passwords().matches(good+"a",hash));
 }
}
