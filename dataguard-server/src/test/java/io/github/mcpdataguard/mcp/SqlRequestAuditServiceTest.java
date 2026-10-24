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
 private DeniedSqlAlertService alerts;
 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);
  identity=mock(AgentPrincipalResolver.class);
  when(identity.requireActiveAgent()).thenReturn(new AgentPrincipal(UUID.randomUUID(),UUID.randomUUID()));
  alerts=mock(DeniedSqlAlertService.class);
  service=new SqlRequestAuditService(jdbc,identity,mock(DeniedSqlAuditChain.class),alerts);
 }
 @Test void invalidDatasourceDoesNotWriteAudit(){
  service.recordDeniedCheck("invalid","db","SELECT 1",Map.of("decision","DENY"));
  verifyNoInteractions(jdbc,alerts);
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
  verifyNoInteractions(alerts);
 }
 @Test void onlySuccessfullyAuditedDenialCreatesAlert(){
  UUID id=UUID.randomUUID();
  when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
  service.recordDeniedCheck(id.toString(),"db","SELECT id FROM report LIMIT 1",
    Map.of("decision","DENY","code","COLUMN_NOT_AUTHORIZED"));
  verify(alerts).record(any(UUID.class),eq(id),eq("COLUMN_NOT_AUTHORIZED"));
 }
}
