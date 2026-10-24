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
 @Test void cancellationRejectsEmptyReasonWithoutDatabaseAccess(){
  var error=assertThrows(ResponseStatusException.class,()->
    controller.cancel(jwt,UUID.randomUUID(),
      new ApprovalDecisionController.DecisionInput(" ",0)));
  assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
  verifyNoInteractions(jdbc);
 }
 @Test void cancellationRejectsStaleOrAlreadyScheduledRequests(){
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
    .thenReturn(List.of());
  var error=assertThrows(ResponseStatusException.class,()->
    controller.cancel(jwt,UUID.randomUUID(),
      new ApprovalDecisionController.DecisionInput("safe cancel",3)));
  assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void cancellationWritesActionAuditAndOutboxTogether(){
  UUID approval=UUID.randomUUID();
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
    .thenReturn(List.of(approval));
  when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
  var output=controller.cancel(jwt,approval,
    new ApprovalDecisionController.DecisionInput("withdrawn",2));
  assertEquals("CANCELLED",output.get("approvalStatus"));
  assertEquals(false,output.get("sqlExecutionEnabled"));
  verify(jdbc,times(4)).update(anyString(),any(Object[].class));
 }
 @Test void unavailableOrStaleCannotBeRejected(){
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
  var ex=assertThrows(ResponseStatusException.class,()->
    controller.reject(jwt,UUID.randomUUID(),new ApprovalDecisionController.DecisionInput("unsafe",1)));
  assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void selfReviewIsForbidden(){
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(inv->{
   RowMapper<?> mapper=inv.getArgument(1);
   java.sql.ResultSet rs=mock(java.sql.ResultSet.class);
   when(rs.getBoolean(1)).thenReturn(false);
   when(rs.getString(2)).thenReturn("synthetic");
   when(rs.getString(3)).thenReturn("LOW");
   return List.of(mapper.mapRow(rs,0));
  });
  var ex=assertThrows(ResponseStatusException.class,()->
    controller.reject(jwt,UUID.randomUUID(),new ApprovalDecisionController.DecisionInput("unsafe",1)));
  assertEquals(HttpStatus.FORBIDDEN,ex.getStatusCode());
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
}
