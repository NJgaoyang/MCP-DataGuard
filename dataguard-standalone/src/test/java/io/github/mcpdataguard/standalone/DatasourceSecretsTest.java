package io.github.mcpdataguard.standalone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class DatasourceSecretsTest {
 @TempDir Path directory;
 @Test void encryptsAndCanDecryptAfterRestart() throws Exception {
  Path file=directory.resolve("credentials.key");
  var secrets=new DatasourceSecrets(file.toString());
  String encrypted=secrets.encrypt("source-1","123456");
  assertTrue(encrypted.startsWith("v1:"));
  assertNotEquals("123456",encrypted);
  assertNotEquals(encrypted,secrets.encrypt("source-1","123456"));
  assertEquals("123456",new DatasourceSecrets(file.toString()).decrypt("source-1",encrypted));
  assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
 }
 @Test void rejectsWrongSourceTamperingAndMissingKey() throws Exception {
  Path file=directory.resolve("credentials.key");
  var secrets=new DatasourceSecrets(file.toString());
  String encrypted=secrets.encrypt("source-1","123456");
  assertThrows(ResponseStatusException.class,()->secrets.decrypt("source-2",encrypted));
  byte[] bytes=java.util.Base64.getDecoder().decode(encrypted.substring(3));bytes[bytes.length-1]^=1;
  assertThrows(ResponseStatusException.class,()->secrets.decrypt("source-1","v1:"+java.util.Base64.getEncoder().encodeToString(bytes)));
  Files.delete(file);
  assertThrows(ResponseStatusException.class,()->new DatasourceSecrets(file.toString()).decrypt("source-1",encrypted));
  assertFalse(Files.exists(file));
 }
}
