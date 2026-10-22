package io.github.mcpdataguard.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class NativeTokens {
 public static final String ISSUER="urn:mcp-dataguard:local";
 public static final String AUDIENCE="mcp-dataguard";
 private static final SecureRandom RNG=new SecureRandom();
 private static final BCryptPasswordEncoder PASSWORD_ENCODER=new BCryptPasswordEncoder(12);
 private NativeTokens(){}
 public static String newToken(){
  byte[] b=new byte[32];RNG.nextBytes(b);
  return "dg_"+Base64.getUrlEncoder().withoutPadding().encodeToString(b);
 }
 public static String sha256(String s){
  try {
   return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8)));
  }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 public static BCryptPasswordEncoder encoder(){return PASSWORD_ENCODER;}
 public static void validatePassword(String password){
  if(password==null || password.getBytes(StandardCharsets.UTF_8).length<14 ||
      password.getBytes(StandardCharsets.UTF_8).length>72 ||
      password.chars().anyMatch(c->Character.isISOControl((char)c)))
   throw new IllegalArgumentException("Password must be 14-72 UTF-8 bytes without control characters");
 }
}
