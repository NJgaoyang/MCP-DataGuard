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

class AgentColumnGrantControllerTest {
 private JdbcTemplate jdbc;
 private UserPrincipalResolver users;
 private AgentColumnGrantController controller;
 private final UUID org=UUID.randomUUID(), userId=UUID.randomUUID();
 private final UUID agent=UUID.randomUUID(), datasource=UUID.randomUUID();
 private final Jwt jwt=mock(Jwt.class);

 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);
  users=mock(UserPrincipalResolver.class);
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(org,userId,"tester","Tester","native","subject"));
  controller=new AgentColumnGrantController(jdbc,users);
 }

 @Test void rejectsWildcardAndUnsafeNamesBeforeAnyDatabaseChanges(){
  for(String column:new String[]{"*","secret;DROP TABLE users","a.b",""}) {
   ResponseStatusException ex=assertThrows(ResponseStatusException.class,()->
    controller.save(jwt,agent,new AgentColumnGrantController.GrantInput(datasource,"orders",column,"ALLOW")));
   assertEquals(HttpStatus.BAD_REQUEST,ex.getStatusCode());
  }
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void doesNotGrantForeignOrDisabledResources(){
  when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(false);
  ResponseStatusException ex=assertThrows(ResponseStatusException.class,()->
   controller.save(jwt,agent,new AgentColumnGrantController.GrantInput(datasource,"orders","amount","ALLOW")));
  assertEquals(HttpStatus.NOT_FOUND,ex.getStatusCode());
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void rejectsInvalidDecisionAndMissingDatasource(){
  ResponseStatusException ex=assertThrows(ResponseStatusException.class,()->
   controller.save(jwt,agent,new AgentColumnGrantController.GrantInput(null,"orders","amount","ALLOW")));
  assertEquals(HttpStatus.BAD_REQUEST,ex.getStatusCode());
  assertThrows(ResponseStatusException.class,()->
   controller.save(jwt,agent,new AgentColumnGrantController.GrantInput(datasource,"orders","amount","WRITE")));
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void validatesManagementPermissionBeforeResourceLookup(){
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(org,userId,"tester","Tester","native","subject"));
  doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(users)
   .requirePermission(any(),eq("agent:permission:write"));
  assertThrows(ResponseStatusException.class,()->
   controller.save(jwt,agent,new AgentColumnGrantController.GrantInput(datasource,"orders","amount","ALLOW")));
  verifyNoInteractions(jdbc);
 }
}
