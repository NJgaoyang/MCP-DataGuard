package io.github.mcpdataguard.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class NativeTokens {
 public static final String ISSUER="https://mcp-dataguard.invalid/local";
 public static final String AUDIENCE="mcp-dataguard";
 private static final SecureRandom RANDOM=new SecureRandom();
 private static final BCryptPasswordEncoder ENCODER=new BCryptPasswordEncoder(12);
 private NativeTokens(){}
 public static String random(){
  byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
  return "dg_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
 }
 public static String digest(String token){
  try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest(token.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 public static BCryptPasswordEncoder passwords(){return ENCODER;}
 public static void requireStrongPassword(String value){
  if(value==null||value.length()<12||value.getBytes(StandardCharsets.UTF_8).length>72||
   !value.matches("(?s).*\\p{Lu}.*")||!value.matches("(?s).*\\p{Ll}.*")||
   !value.matches("(?s).*\\d.*")||!value.matches("(?s).*[^\\p{L}\\p{N}].*")||
   value.chars().anyMatch(Character::isISOControl))
   throw new IllegalArgumentException("Password requires 12+ characters, upper/lower case, digit and symbol (72-byte max)");
 }
}
