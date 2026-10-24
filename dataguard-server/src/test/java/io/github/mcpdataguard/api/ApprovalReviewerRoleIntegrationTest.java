package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import io.github.mcpdataguard.workflow.contract.SafeApprovalGraph;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tenant-scoped real PostgreSQL reviewer-role and self-approval regression.
 * Only the disposable 127.0.0.1 PostgreSQL fixture activates this test.
 */
class ApprovalReviewerRoleIntegrationTest {
 @Test void twoIndependentReviewersMustMatchCurrentCertifiedStage(){
  String url=System.getenv("DG_OUTBOX_E2E_URL");
  Assumptions.assumeTrue(url!=null &&
    url.startsWith("jdbc:postgresql://127.0.0.1:5432/dataguard"));
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(
    url+"?currentSchema=platform","postgres","test-only"));
  UUID org=UUID.randomUUID(),owner=UUID.randomUUID(),first=UUID.randomUUID(),
    second=UUID.randomUUID(),wrong=UUID.randomUUID(),agent=UUID.randomUUID(),
    source=UUID.randomUUID(),workflow=UUID.randomUUID(),version=UUID.randomUUID(),
    request=UUID.randomUUID(),approval=UUID.randomUUID();
  db.update("INSERT INTO dg_organization(id,code,name) VALUES(?,?,?)",
    org,"review_"+org.toString().replace("-",""),"Isolated reviewers");
  for(UUID user:List.of(owner,first,second,wrong)){
   db.update("INSERT INTO dg_user(id,org_id,username,display_name) VALUES(?,?,?,?)",
     user,org,"u_"+user.toString().replace("-",""),"Reviewer");
  }
  UUID firstRole=UUID.randomUUID(),secondRole=UUID.randomUUID();
  db.update("INSERT INTO dg_role(id,org_id,code,name) VALUES (?,?,'db-owner','Database Owner')",
    firstRole,org);
  db.update("INSERT INTO dg_role(id,org_id,code,name) VALUES (?,?,'dba','Database Admin')",
    secondRole,org);
  db.update("INSERT INTO dg_user_role(org_id,user_id,role_id) VALUES (?,?,?)",org,first,firstRole);
  db.update("INSERT INTO dg_user_role(org_id,user_id,role_id) VALUES (?,?,?)",org,second,secondRole);
  db.update("INSERT INTO dg_agent(id,org_id,owner_user_id,code,name) VALUES(?,?,?,?,?)",
    agent,org,owner,"owner_agent","Owned Agent");
  db.update("""
    INSERT INTO dg_datasource(id,org_id,owner_user_id,code,name,db_type,
      environment,host,port,database_name,status)
    VALUES(?,?,?,'review_db','Review database','POSTGRESQL',
      'TEST','localhost',5432,'dataguard','ACTIVE')
    """,source,org,owner);
  String xml=SafeApprovalGraph.compile(new SafeApprovalGraph.Design(List.of(
    new SafeApprovalGraph.Step("db-owner","Database owner"),
    new SafeApprovalGraph.Step("dba","Database admin")),false));
  String digest;
  try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
       .digest(xml.getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
  db.update("INSERT INTO dg_workflow_template(id,org_id,code,name,created_by) VALUES(?,?,?,?,?)",
    workflow,org,"role_test","Role test",owner);
  db.update("""
    INSERT INTO dg_workflow_version(id,org_id,workflow_id,revision,bpmn_xml,
      bpmn_digest,flowable_definition_key,status)
    VALUES(?,?,?,1,?,?,'safeWorkflowV2','PUBLISHED')
    """,version,org,workflow,xml,digest);
  db.update("""
    INSERT INTO dg_sql_request(id,org_id,agent_id,datasource_id,tool_name,
      decision,request_status,risk_level,requester_user_id)
    VALUES(?,?,?,?,'submit_change','REQUIRE_APPROVAL','PENDING_APPROVAL','LOW',NULL)
    """,request,org,agent,source);
  db.update("""
    INSERT INTO dg_sql_approval(id,org_id,request_id,workflow_version_id,
      process_instance_id,approval_status,execution_status,expires_at)
    VALUES(?,?,?,?,?,'PENDING','NOT_SCHEDULED',now()+interval '1 day')
    """,approval,org,request,version,UUID.randomUUID().toString());

  UserPrincipalResolver users=mock(UserPrincipalResolver.class);
  Jwt jwt=mock(Jwt.class);
  ApprovalDecisionController controller=new ApprovalDecisionController(db,users);
  java.util.function.Function<UUID,UserPrincipal> asUser=id->
    new UserPrincipal(org,id,"reviewer","Reviewer","native","local");
  when(users.requireUser(jwt)).thenReturn(asUser.apply(owner));
  var ex=assertThrows(ResponseStatusException.class,()->controller.approve(
    jwt,approval,new ApprovalDecisionController.DecisionInput("self approval",0)));
  assertEquals(HttpStatus.FORBIDDEN,ex.getStatusCode(),
    "An Agent owner must not approve their own Agent's request");

  when(users.requireUser(jwt)).thenReturn(asUser.apply(wrong));
  ex=assertThrows(ResponseStatusException.class,()->controller.approve(
    jwt,approval,new ApprovalDecisionController.DecisionInput("wrong role",0)));
  assertEquals(HttpStatus.FORBIDDEN,ex.getStatusCode());

  when(users.requireUser(jwt)).thenReturn(asUser.apply(second));
  ex=assertThrows(ResponseStatusException.class,()->controller.approve(
    jwt,approval,new ApprovalDecisionController.DecisionInput("skip first",0)));
  assertEquals(HttpStatus.FORBIDDEN,ex.getStatusCode());

  when(users.requireUser(jwt)).thenReturn(asUser.apply(first));
  var initial=controller.approve(jwt,approval,
    new ApprovalDecisionController.DecisionInput("first reviewer",0));
  assertEquals("PENDING",initial.get("approvalStatus"));
  assertEquals(1,initial.get("stage"));

  ex=assertThrows(ResponseStatusException.class,()->controller.approve(
    jwt,approval,new ApprovalDecisionController.DecisionInput("same person twice",1)));
  assertEquals(HttpStatus.FORBIDDEN,ex.getStatusCode());

  when(users.requireUser(jwt)).thenReturn(asUser.apply(second));
  var finalDecision=controller.approve(jwt,approval,
    new ApprovalDecisionController.DecisionInput("independent second reviewer",1));
  assertEquals("APPROVED",finalDecision.get("approvalStatus"));
  assertEquals(false,finalDecision.get("executionEnabled"));
  assertEquals("NOT_SCHEDULED",db.queryForObject("""
    SELECT execution_status FROM dg_sql_approval WHERE org_id=? AND id=?
    """,String.class,org,approval));
  assertEquals(2L,db.queryForObject("""
    SELECT count(*) FROM dg_sql_approval_action
    WHERE org_id=? AND approval_id=? AND action='APPROVE'
    """,Long.class,org,approval));
  assertEquals(2L,db.queryForObject("""
    SELECT count(*) FROM dg_outbox_event
    WHERE org_id=? AND aggregate_id=? AND event_type='APPROVAL_STEP_APPROVED'
    """,Long.class,org,approval));
  assertEquals(0L,db.queryForObject("""
    SELECT count(*) FROM dg_execution_task WHERE org_id=?
    """,Long.class,org));
  // This test does not instantiate Flowable. The two fake pending events
  // must not be consumed by subsequent real outbox integration tests sharing
  // the same ephemeral PostgreSQL instance.
  db.update("""
    DELETE FROM dg_outbox_event WHERE org_id=? AND aggregate_id=?
      AND event_type='APPROVAL_STEP_APPROVED'
    """,org,approval);
 }
}
