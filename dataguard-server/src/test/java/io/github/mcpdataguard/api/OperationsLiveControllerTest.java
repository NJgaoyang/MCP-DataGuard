package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OperationsLiveControllerTest {
 @Test void rollingMetricsAreScopedAndContainNoRawSql(){
  JdbcTemplate db=mock(JdbcTemplate.class);
  UserPrincipalResolver users=mock(UserPrincipalResolver.class);
  Jwt jwt=mock(Jwt.class);
  UUID org=UUID.randomUUID(),user=UUID.randomUUID();
  when(users.requireUser(jwt)).thenReturn(
    new UserPrincipal(org,user,"admin","Admin","issuer","subject"));
  when(db.queryForObject(anyString(),ArgumentMatchers.<RowMapper<long[]>>any(),eq(org)))
    .thenReturn(new long[]{300,30,120});
  when(db.queryForObject(anyString(),eq(Long.class),eq(org))).thenReturn(2L,5L);
  var result=new OperationsLiveController(db,users).live(jwt);
  assertEquals(300L,result.get("requests"));
  assertEquals(1.0,result.get("qps"));
  assertEquals(0.1,result.get("failureRate"));
  assertEquals(120L,result.get("p95ReadLatencyMs"));
  assertEquals(2L,result.get("pendingApprovals"));
  assertEquals(5L,result.get("openAlerts"));
  assertFalse(result.containsKey("sql"));
  verify(users).requirePermission(any(),eq("identity:admin"));
  verify(db,times(2)).queryForObject(anyString(),eq(Long.class),eq(org));
 }
 @Test void deniesUserWithoutMonitoringRoleBeforeAnySql(){
  JdbcTemplate db=mock(JdbcTemplate.class);
  UserPrincipalResolver users=mock(UserPrincipalResolver.class);
  Jwt jwt=mock(Jwt.class);
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(
    UUID.randomUUID(),UUID.randomUUID(),"viewer","Viewer","issuer","subject"));
  doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(users)
    .requirePermission(any(),eq("identity:admin"));
  assertThrows(ResponseStatusException.class,()->
    new OperationsLiveController(db,users).live(jwt));
  verifyNoInteractions(db);
 }
}
