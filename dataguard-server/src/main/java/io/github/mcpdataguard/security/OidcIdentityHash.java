package io.github.mcpdataguard.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical SHA-256 of [uint32 big-endian length][UTF-8 issuer]
 * [uint32 big-endian length][UTF-8 subject].
 * Never concatenate raw strings without lengths. */
public final class OidcIdentityHash {
 private OidcIdentityHash() {}
 public static String compute(String issuer,String subject) {
  if (issuer==null || subject==null || issuer.isBlank() || subject.isBlank())
   throw new IllegalArgumentException("issuer and subject must be nonempty");
  byte[] i=issuer.getBytes(StandardCharsets.UTF_8);
  byte[] s=subject.getBytes(StandardCharsets.UTF_8);
  ByteBuffer bytes=ByteBuffer.allocate(8+i.length+s.length);
  bytes.putInt(i.length).put(i).putInt(s.length).put(s);
  try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
}
