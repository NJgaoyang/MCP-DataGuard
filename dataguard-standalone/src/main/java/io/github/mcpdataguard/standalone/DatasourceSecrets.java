package io.github.mcpdataguard.standalone;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DatasourceSecrets {
 private final Path keyFile;
 private final SecureRandom random=new SecureRandom();
 private byte[] key;
 public DatasourceSecrets(@Value("${dataguard.credentials.key-file:/var/lib/dataguard/credentials.key}") String keyFile){this.keyFile=Path.of(keyFile);}
 private synchronized byte[] key() throws Exception {
  if(key!=null)return key;
  if(!Files.exists(keyFile)){
   Files.createDirectories(keyFile.toAbsolutePath().getParent());
   byte[] generated=new byte[32];random.nextBytes(generated);
   try(var channel=Files.newByteChannel(keyFile,java.util.Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){
    ByteBuffer data=ByteBuffer.wrap(generated);while(data.hasRemaining())channel.write(data);
   }catch(java.nio.file.FileAlreadyExistsException ignored){}
  }
  if(Files.isSymbolicLink(keyFile)||!Files.isRegularFile(keyFile))throw new java.io.IOException("Invalid credential key file");
  var permissions=Files.getPosixFilePermissions(keyFile);
  if(!permissions.equals(PosixFilePermissions.fromString("rw-------")))throw new java.io.IOException("Credential key must be owner-only");
  byte[] loaded=Files.readAllBytes(keyFile);
  if(loaded.length!=32)throw new java.io.IOException("Invalid credential key length");
  key=loaded;return key;
 }
 public String encrypt(String sourceId,String password){
  try{
   byte[] iv=new byte[12];random.nextBytes(iv);
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,iv));
   cipher.updateAAD(sourceId.getBytes(StandardCharsets.UTF_8));
   byte[] encrypted=cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
   return "v1:"+Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length+encrypted.length).put(iv).put(encrypted).array());
  }catch(Exception ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"数据库密码加密失败，请检查服务器凭据密钥配置");}
 }
 String decrypt(String sourceId,String encrypted){
  try{
   if(!Files.exists(keyFile))throw new java.io.IOException("Credential key unavailable");
   if(!encrypted.startsWith("v1:"))throw new IllegalArgumentException();
   byte[] data=Base64.getDecoder().decode(encrypted.substring(3));
   if(data.length<28)throw new IllegalArgumentException();
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,data,0,12));
   cipher.updateAAD(sourceId.getBytes(StandardCharsets.UTF_8));
   return new String(cipher.doFinal(data,12,data.length-12),StandardCharsets.UTF_8);
  }catch(Exception ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"无法读取数据源密码，请检查服务器凭据密钥");}
 }
 public String resolve(String sourceId,Map<String,Object> source){
  Object encrypted=source.get("encrypted_password");
  if(encrypted instanceof String value&&!value.isEmpty())return decrypt(sourceId,value);
  Object env=source.get("password_env");
  String password=env instanceof String name&&!name.isBlank()?System.getenv(name):null;
  if(password==null||password.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"数据源密码环境变量未配置");
  return password;
 }
}
