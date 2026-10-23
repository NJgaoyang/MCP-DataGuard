package io.github.mcpdataguard.mcp;

import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionEligibilityServiceTest {
 @Test void invalidIntentNeverHitsDatabase(){
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  var result=new ExecutionEligibilityService(jdbc).inspect(
     UUID.randomUUID(),UUID.randomUUID(),"malformed","malformed");
  assertFalse(result.eligible());
  assertEquals("INVALID_EXECUTION_INTENT",result.reason());
  verifyNoInteractions(jdbc);
 }
 @Test void missingOrStaleApprovalMustNotAuthorizeExecution(){
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
    .thenReturn(List.of());
  var result=new ExecutionEligibilityService(jdbc).inspect(
    UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),"b".repeat(64));
  assertFalse(result.eligible());
  assertEquals("APPROVAL_NOT_ELIGIBLE",result.reason());
 }
}
