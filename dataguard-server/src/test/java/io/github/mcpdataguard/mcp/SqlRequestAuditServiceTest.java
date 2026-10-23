package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlRequestAuditServiceTest {
 private JdbcTemplate jdbc;
 private AgentPrincipalResolver identity;
 private SqlRequestAuditService service;
 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);
  identity=mock(AgentPrincipalResolver.class);
  when(identity.requireActiveAgent()).thenReturn(new AgentPrincipal(UUID.randomUUID(),UUID.randomUUID()));
  service=new SqlRequestAuditService(jdbc,identity);
 }
 @Test void invalidDatasourceDoesNotWriteAudit(){
  service.recordDeniedCheck("invalid","db","SELECT 1",Map.of("decision","DENY"));
  verifyNoInteractions(jdbc);
 }
 @Test void storesFingerprintNotSqlText(){
  String rawSql="SELECT account_number FROM secret_accounts";
  service.recordDeniedCheck(UUID.randomUUID().toString(),"db",rawSql,Map.of("decision","DENY"));
  org.mockito.ArgumentCaptor<Object[]> args=org.mockito.ArgumentCaptor.forClass(Object[].class);
  verify(jdbc).update(anyString(),args.capture());
  Object[] values=args.getValue();
  String digest=(String) values[4];
  assertEquals(64,digest.length());
  for(Object value:values)assertNotEquals(rawSql,value);
 }
}
