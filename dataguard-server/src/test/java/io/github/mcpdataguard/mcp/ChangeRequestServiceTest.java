package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChangeRequestServiceTest {
 private JdbcTemplate jdbc;
 private ChangeRequestService requests;
 private final String datasource=UUID.randomUUID().toString(), key=UUID.randomUUID().toString();
 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);
  AgentPrincipalResolver agents=mock(AgentPrincipalResolver.class);
  when(agents.requireActiveAgent()).thenReturn(new AgentPrincipal(UUID.randomUUID(),UUID.randomUUID()));
  requests=new ChangeRequestService(jdbc,agents,new ApprovalPayloadCrypto(""));
 }
 @Test void refusesMalformedOrUnboundedChangeShapesBeforeDatabaseAccess(){
  for(String sql:List.of("DROP TABLE users","UPDATE users SET admin=1",
      "UPDATE users SET admin=? WHERE id=?;DELETE FROM users",
      "UPDATE users SET admin=? WHERE id=? -- bypass")){
   var result=requests.submit(datasource,"db",sql,List.of(1,2),"emergency change",key);
   assertEquals("DENY",result.get("decision"));
  }
  verifyNoInteractions(jdbc);
 }
 @Test void refusesValidChangeWithoutConfiguredEncryption(){
  var result=requests.submit(datasource,"db","UPDATE orders SET status=? WHERE id=?",
    List.of("PAID",1),"correct order",key);
  assertEquals("APPROVAL_ENCRYPTION_NOT_CONFIGURED",result.get("code"));
  verifyNoInteractions(jdbc);
 }
 @Test void doesNotLeakAnotherAgentsStatusForInvalidIdentifiers(){
  var result=requests.status("not-a-uuid");
  assertEquals("DENY",result.get("decision"));
  verifyNoInteractions(jdbc);
 }
}
