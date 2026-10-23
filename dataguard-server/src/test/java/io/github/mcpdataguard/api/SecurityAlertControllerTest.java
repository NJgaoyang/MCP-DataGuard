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

class SecurityAlertControllerTest {
 private JdbcTemplate jdbc;
 private UserPrincipalResolver users;
 private SecurityAlertController controller;
 private final Jwt jwt=mock(Jwt.class);
 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);users=mock(UserPrincipalResolver.class);
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(
    UUID.randomUUID(),UUID.randomUUID(),"admin","Admin","issuer","subject"));
  controller=new SecurityAlertController(jdbc,users);
 }
 @Test void onlyOpenSameTenantAlertCanBeAcknowledged(){
  when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
  var ex=assertThrows(ResponseStatusException.class,()->
    controller.acknowledge(jwt,UUID.randomUUID()));
  assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
  verify(jdbc,times(1)).update(anyString(),any(Object[].class));
 }
 @Test void requiresAdminBeforeTouchingAlert(){
  doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(users)
    .requirePermission(any(),eq("identity:admin"));
  assertThrows(ResponseStatusException.class,()->
    controller.acknowledge(jwt,UUID.randomUUID()));
  verifyNoInteractions(jdbc);
 }
}
