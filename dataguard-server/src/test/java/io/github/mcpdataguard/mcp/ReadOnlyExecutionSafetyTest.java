package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadOnlyExecutionSafetyTest {
 @Test void disabledMcpExecutorCannotReachControlOrTargetDatabase(){
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  AgentPrincipalResolver agents=mock(AgentPrincipalResolver.class);
  when(agents.requireActiveAgent()).thenReturn(new AgentPrincipal(UUID.randomUUID(),UUID.randomUUID()));
  IsolatedReadOnlyExecutor executor=mock(IsolatedReadOnlyExecutor.class);
  ReadReceiptService receipts=mock(ReadReceiptService.class);
  ReadOnlyQueryService query=new ReadOnlyQueryService(jdbc,agents,executor,receipts,false);
  var result=query.run(UUID.randomUUID().toString(),"db","SELECT id FROM orders LIMIT 2",
      List.of(),2,null);
  assertEquals("READ_EXECUTION_DISABLED",result.get("code"));
  verifyNoInteractions(jdbc,executor,receipts);
 }
 @Test void maskProtectsScalarAndBinaryDataWithoutChangingNullSemantics(){
  assertNull(IsolatedReadOnlyExecutor.maskValue(null));
  assertEquals("[REDACTED]",IsolatedReadOnlyExecutor.maskValue("private"));
  assertEquals("[REDACTED]",IsolatedReadOnlyExecutor.maskValue(123456789));
  assertEquals("[REDACTED]",IsolatedReadOnlyExecutor.maskValue(1.15));
  assertEquals("[REDACTED]",IsolatedReadOnlyExecutor.maskValue(true));
  assertEquals("[REDACTED]",IsolatedReadOnlyExecutor.maskValue(new byte[]{1,2,3}));
 }
 @Test void hostNotAllowlistedCannotOpenReadConnection(){
  IsolatedReadOnlyExecutor isolated=new IsolatedReadOnlyExecutor("safe.example.com");
  var target=new IsolatedReadOnlyExecutor.Target("unsafe.example.com",5432,
    "reports","reader","temporary");
  var req=new IsolatedReadOnlyExecutor.Request("SELECT id FROM orders LIMIT 1",
    List.of(),Set.of("id"),1,100,2);
  assertThrows(SecurityException.class,()->isolated.execute(target,req));
 }
 @Test void invalidRowOrByteBudgetsCannotOpenReadConnection(){
  IsolatedReadOnlyExecutor isolated=new IsolatedReadOnlyExecutor("safe.example.com");
  var target=new IsolatedReadOnlyExecutor.Target("safe.example.com",5432,
    "reports","reader","temporary");
  var req=new IsolatedReadOnlyExecutor.Request("SELECT id FROM orders LIMIT 1",
    List.of(),Set.of("id"),101,0,2);
  assertThrows(IllegalArgumentException.class,()->isolated.execute(target,req));
 }
}
