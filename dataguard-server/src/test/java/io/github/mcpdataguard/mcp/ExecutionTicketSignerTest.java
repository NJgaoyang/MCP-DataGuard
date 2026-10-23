package io.github.mcpdataguard.mcp;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionTicketSignerTest {
 private static ExecutionTicketSigner signer(int tweak){
  byte[] key=new byte[32];
  for(int i=0;i<key.length;i++)key[i]=(byte)(i+1+tweak);
  return new ExecutionTicketSigner(Base64.getEncoder().encodeToString(key));
 }
 @Test void onlyMatchingOrganizationApprovalSqlAndTaskPass(){
  var signer=signer(0);
  UUID org=UUID.randomUUID(),approval=UUID.randomUUID(),
    request=UUID.randomUUID(),task=UUID.randomUUID();
  String sql="a".repeat(64),hmac="b".repeat(64);
  String ticket=signer.sign(org,approval,request,sql,hmac,task);
  assertTrue(signer.verify(org,approval,request,sql,hmac,task,ticket));
  assertFalse(signer.verify(UUID.randomUUID(),approval,request,sql,hmac,task,ticket));
  assertFalse(signer.verify(org,UUID.randomUUID(),request,sql,hmac,task,ticket));
  assertFalse(signer.verify(org,approval,request,"c".repeat(64),hmac,task,ticket));
  assertFalse(signer.verify(org,approval,request,sql,hmac,UUID.randomUUID(),ticket));
  assertFalse(signer(1).verify(org,approval,request,sql,hmac,task,ticket));
 }
 @Test void noKeyNoTicket(){
  var signer=new ExecutionTicketSigner("");
  assertFalse(signer.configured());
  assertThrows(SecurityException.class,()->
    signer.sign(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
      "a".repeat(64),"b".repeat(64),UUID.randomUUID()));
 }
}
