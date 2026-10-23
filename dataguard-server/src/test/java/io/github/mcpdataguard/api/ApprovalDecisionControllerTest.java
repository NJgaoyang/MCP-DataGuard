package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovalDecisionControllerTest {
 private JdbcTemplate jdbc;
 private UserPrincipalResolver users;
 private ApprovalDecisionController controller;
 private final Jwt jwt=mock(Jwt.class);
 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);users=mock(UserPrincipalResolver.class);
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(
    UUID.randomUUID(),UUID.randomUUID(),"reviewer","Reviewer","native","subject"));
  controller=new ApprovalDecisionController(jdbc,users);
 }
 @Test void invalidReasonDoesNotTouchApproval(){
  var ex=assertThrows(ResponseStatusException.class,()->
    controller.reject(jwt,UUID.randomUUID(),new ApprovalDecisionController.DecisionInput(" ",0)));
  assertEquals(HttpStatus.BAD_REQUEST,ex.getStatusCode());
  verifyNoInteractions(jdbc);
 }
 @Test void unavailableOrStaleCannotBeRejected(){
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
  var ex=assertThrows(ResponseStatusException.class,()->
    controller.reject(jwt,UUID.randomUUID(),new ApprovalDecisionController.DecisionInput("unsafe",1)));
  assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void selfReviewIsForbidden(){
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(false));
  var ex=assertThrows(ResponseStatusException.class,()->
    controller.reject(jwt,UUID.randomUUID(),new ApprovalDecisionController.DecisionInput("unsafe",1)));
  assertEquals(HttpStatus.FORBIDDEN,ex.getStatusCode());
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
}
