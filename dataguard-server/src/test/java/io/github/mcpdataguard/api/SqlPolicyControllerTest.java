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
import static org.mockito.Mockito.*;

class SqlPolicyControllerTest {
 private JdbcTemplate jdbc;
 private UserPrincipalResolver users;
 private SqlPolicyController controller;
 private final Jwt jwt=mock(Jwt.class);
 private final UUID policyId=UUID.randomUUID();

 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);users=mock(UserPrincipalResolver.class);
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(
   UUID.randomUUID(),UUID.randomUUID(),"admin","Admin","native","subject"));
  controller=new SqlPolicyController(jdbc,users);
 }
 @Test void disallowsAllowRuleFromDraftVersion(){
  ResponseStatusException e=assertThrows(ResponseStatusException.class,()->
   controller.newVersion(jwt,policyId,new SqlPolicyController.RuleInput("ALLOW",100,"orders","DEV")));
  assertEquals(HttpStatus.BAD_REQUEST,e.getStatusCode());
  verifyNoInteractions(jdbc);
 }
 @Test void rejectsWildcardAndInvalidEnvironment(){
  assertThrows(ResponseStatusException.class,()->
   controller.newVersion(jwt,policyId,new SqlPolicyController.RuleInput("DENY",100,"*","DEV")));
  assertThrows(ResponseStatusException.class,()->
   controller.newVersion(jwt,policyId,new SqlPolicyController.RuleInput("DENY",100,"orders","ALL")));
  assertThrows(ResponseStatusException.class,()->
   controller.newVersion(jwt,policyId,new SqlPolicyController.RuleInput("DENY",0,"orders","DEV")));
  verifyNoInteractions(jdbc);
 }
 @Test void rejectsMissingDecision(){
  assertThrows(ResponseStatusException.class,()->
   controller.newVersion(jwt,policyId,new SqlPolicyController.RuleInput(null,100,"orders","DEV")));
  verifyNoInteractions(jdbc);
 }
}
