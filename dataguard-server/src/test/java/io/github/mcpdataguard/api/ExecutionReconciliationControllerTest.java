package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionReconciliationControllerTest {
 private record Setup(ExecutionReconciliationController controller,JdbcTemplate jdbc,Jwt jwt){}
 private static Setup setup(){
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  UserPrincipalResolver users=mock(UserPrincipalResolver.class);
  Jwt jwt=mock(Jwt.class);
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(
    UUID.randomUUID(),UUID.randomUUID(),"admin","Admin","issuer","subject"));
  return new Setup(new ExecutionReconciliationController(jdbc,users),jdbc,jwt);
 }
 @Test void invalidEvidenceNeverTouchesDatabase(){
  Setup s=setup();
  UUID task=UUID.randomUUID();
  for(var entry:List.of(
    new ExecutionReconciliationController.Attestation("../bad","COMMITTED",0),
    new ExecutionReconciliationController.Attestation("evidence:record/12345","RETRY",0),
    new ExecutionReconciliationController.Attestation("evidence:record/12345","COMMITTED",-1))){
   var error=assertThrows(ResponseStatusException.class,
     ()->s.controller().attest(s.jwt(),task,entry));
   assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
  }
  verifyNoInteractions(s.jdbc());
 }
 @Test void cannotAttestStaleOrNonUnknownTask(){
  Setup s=setup();
  when(s.jdbc().query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),
       any(Object[].class))).thenReturn(List.of());
  var error=assertThrows(ResponseStatusException.class,()->
    s.controller().attest(s.jwt(),UUID.randomUUID(),
      new ExecutionReconciliationController.Attestation(
        "external:readonly:proof12345","INDETERMINATE",0)));
  assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
 }
}
