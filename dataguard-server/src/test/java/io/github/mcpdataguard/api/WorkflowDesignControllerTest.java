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

class WorkflowDesignControllerTest {
 private WorkflowDesignController controller;
 private JdbcTemplate jdbc;
 private final Jwt jwt=mock(Jwt.class);
 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);
  var users=mock(UserPrincipalResolver.class);
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(
    UUID.randomUUID(),UUID.randomUUID(),"admin","Admin","issuer","subject"));
  controller=new WorkflowDesignController(jdbc,users);
 }
 @Test void rejectsUnsafeBpmnLabelsBeforeWriting(){
  for(String bad:new String[]{"<script>alert(1)</script>","invalid!label","bad@expression"}){
   var e=assertThrows(ResponseStatusException.class,()->
     controller.create(jwt,new WorkflowDesignController.DesignInput(
       "review_safe","Approval",bad,"DBA Review")));
   assertEquals(HttpStatus.BAD_REQUEST,e.getStatusCode());
  }
  verifyNoInteractions(jdbc);
 }
 @Test void publishingInvalidRevisionHasNoDatabaseEffects(){
  var e=assertThrows(ResponseStatusException.class,()->
   controller.publish(jwt,UUID.randomUUID(),
       new WorkflowDesignController.PublishInput(0,0)));
  assertEquals(HttpStatus.BAD_REQUEST,e.getStatusCode());
  verifyNoInteractions(jdbc);
 }
}
