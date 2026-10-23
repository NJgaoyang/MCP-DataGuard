package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PolicyLifecycleTest {
 private JdbcTemplate jdbc;
 private UserPrincipalResolver users;
 private SqlPolicyController policies;
 private OperationsReadController operations;
 private final Jwt jwt=mock(Jwt.class);
 private final UUID org=UUID.randomUUID(), user=UUID.randomUUID();
 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);users=mock(UserPrincipalResolver.class);
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(org,user,"ops","Operator","issuer","subject"));
  policies=new SqlPolicyController(jdbc,users);
  operations=new OperationsReadController(jdbc,users);
 }
 @Test void publicationRequiresAnExistingCurrentDraftRevision(){
  ResponseStatusException ex=assertThrows(ResponseStatusException.class,()->
   policies.publish(jwt,UUID.randomUUID(),new SqlPolicyController.LifecycleInput(1)));
  assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void publicationRejectsNegativeVersions(){
  ResponseStatusException ex=assertThrows(ResponseStatusException.class,()->
   policies.publish(jwt,UUID.randomUUID(),new SqlPolicyController.LifecycleInput(-1)));
  assertEquals(HttpStatus.BAD_REQUEST,ex.getStatusCode());
  verifyNoInteractions(jdbc);
 }
 @Test void monitoringRejectsInvalidLimitsBeforeQuerying(){
  assertThrows(ResponseStatusException.class,()->operations.requests(jwt,101,0));
  assertThrows(ResponseStatusException.class,()->operations.approvals(jwt,50,-1));
  verifyNoInteractions(jdbc);
 }
 @Test void monitoringChecksAdminPermission(){
  doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(users)
    .requirePermission(any(),eq("identity:admin"));
  assertThrows(ResponseStatusException.class,()->operations.summary(jwt));
  verifyNoInteractions(jdbc);
 }
}
