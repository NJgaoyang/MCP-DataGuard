package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.NativeSessionFilter;
import io.github.mcpdataguard.security.NativeTokens;
import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.workflow.ApprovalOutboxWorker;
import io.github.mcpdataguard.workflow.ApprovalTimeoutWorker;
import io.github.mcpdataguard.workflow.CertifiedWorkflowDeployment;
import io.github.mcpdataguard.workflow.ReviewProcessService;
import io.github.mcpdataguard.workflow.contract.SafeApprovalGraph;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cross-module, disposable PostgreSQL + genuine BCrypt native login and
 * opaque bearer session -> platform approval API -> durable Outbox ->
 * real Flowable engine. Every DB object is synthetic, no write executor runs.
 */
class NativeLoginFlowableOutboxIntegrationTest {
 private static final String PASSWORD="SyntheticBusiness-2026!Only";
 private final UUID org=UUID.randomUUID(),owner=UUID.randomUUID(),
   first=UUID.randomUUID(),second=UUID.randomUUID(),outsider=UUID.randomUUID(),
   agent=UUID.randomUUID(),source=UUID.randomUUID(),template=UUID.randomUUID(),
   version=UUID.randomUUID();
 private String code;
 private JdbcTemplate db;
 private ApprovalDecisionController approvals;
 private ApprovalOutboxWorker outbox;
 private ApprovalTimeoutWorker timeouts;
 private NativeAuthController auth;
 private String platformUrl;

 @Test void realNativeBearerReviewersAdvanceRejectAndExpireFlowable() throws Exception {
  platformUrl=System.getenv("DG_OUTBOX_E2E_URL");
  Assumptions.assumeTrue(platformUrl!=null &&
    platformUrl.startsWith("jdbc:postgresql://127.0.0.1:5432/dataguard"));
  db=new JdbcTemplate(new DriverManagerDataSource(
    platformUrl+"?currentSchema=platform","postgres","test-only"));
  seed();
  auth=new NativeAuthController(db);
  approvals=new ApprovalDecisionController(db,new UserPrincipalResolver(db));
  // No JWT identities are mocked: bearer tokens are issued by real login
  // and then re-read/validated by NativeSessionFilter against PostgreSQL.
  Jwt ownerToken=authenticate(owner),one=authenticate(first),
      two=authenticate(second),wrong=authenticate(outsider);
  assertEquals(4L,db.queryForObject("""
    SELECT count(*) FROM dg_native_session WHERE org_id=? AND revoked_at IS NULL
    """,Long.class,org));

  // Other isolated integration suites also create fresh H2 engines whose
  // numeric IDs restart from 1. The shared real PostgreSQL approval table
  // requires globally unique Flowable instance identifiers across suites.
  var flowConfig=ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration();
  flowConfig.setIdGenerator(new org.flowable.common.engine.impl.persistence.StrongUuidGenerator());
  var engine=flowConfig
    .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
    .buildProcessEngine();
  try {
   var process=new ReviewProcessService(engine.getRuntimeService(),engine.getTaskService());
   outbox=new ApprovalOutboxWorker(platformUrl,"postgres","test-only",
      process,engine.getRuntimeService(),engine.getTaskService(),
      new CertifiedWorkflowDeployment(engine.getRepositoryService(),
        engine.getRuntimeService(),true));
   timeouts=new ApprovalTimeoutWorker(platformUrl,"postgres","test-only");

   UUID approved=insertApproval();
   String firstInstance=started(approved);
   assertEquals("review1",process.pending(firstInstance,org.toString()).get(0).stage());
   assertEquals(1,approvals.myInbox(one).size());
   assertEquals(0,approvals.myInbox(two).size());
   assertEquals(0,approvals.myInbox(ownerToken).size());
   assertEquals(0,approvals.myInbox(wrong).size());
   var denied=assertThrows(ResponseStatusException.class,()->
      approvals.approve(two,approved,new ApprovalDecisionController.DecisionInput("skip stage",0)));
   assertEquals(HttpStatus.FORBIDDEN,denied.getStatusCode());
   denied=assertThrows(ResponseStatusException.class,()->
      approvals.approve(ownerToken,approved,new ApprovalDecisionController.DecisionInput("self approval",0)));
   assertEquals(HttpStatus.FORBIDDEN,denied.getStatusCode());
   assertEquals("PENDING",approvals.approve(one,approved,
      new ApprovalDecisionController.DecisionInput("first reviewer accepted",0)).get("approvalStatus"));
   published(approved,1);
   assertEquals("review2",process.pending(firstInstance,org.toString()).get(0).stage());
   assertEquals(0,approvals.myInbox(one).size());
   assertEquals(1,approvals.myInbox(two).size());
   assertEquals("APPROVED",approvals.approve(two,approved,
      new ApprovalDecisionController.DecisionInput("DBA accepted",1)).get("approvalStatus"));
   published(approved,2);
   assertTrue(engine.getRuntimeService().createProcessInstanceQuery()
      .processInstanceId(firstInstance).list().isEmpty());
   assertEquals(2L,db.queryForObject("""
     SELECT count(*) FROM dg_sql_approval_action WHERE org_id=? AND approval_id=?
       AND action='APPROVE' AND flowable_task_id NOT LIKE 'platform:%'
     """,Long.class,org,approved));
   assertEquals(2L,db.queryForObject("""
     SELECT count(*) FROM dg_outbox_event WHERE org_id=? AND aggregate_id=?
       AND event_type='APPROVAL_STEP_APPROVED' AND state='PUBLISHED'
     """,Long.class,org,approved));

   UUID rejected=insertApproval();
   String rejectInstance=started(rejected);
   assertEquals("REJECTED",approvals.reject(one,rejected,
     new ApprovalDecisionController.DecisionInput("risk evidence missing",0)).get("approvalStatus"));
   awaitPublished(rejected,"APPROVAL_REJECTED");
   assertTrue(engine.getRuntimeService().createProcessInstanceQuery()
     .processInstanceId(rejectInstance).list().isEmpty());

   UUID expired=insertApproval();
   String expireInstance=started(expired);
   db.update("""
     UPDATE dg_sql_approval SET expires_at=now()-interval '1 minute'
     WHERE org_id=? AND id=?
     """,org,expired);
   timeouts.expire();
   awaitPublished(expired,"APPROVAL_REJECTED");
   assertEquals("EXPIRED",approvalStatus(expired));
   assertTrue(engine.getRuntimeService().createProcessInstanceQuery()
     .processInstanceId(expireInstance).list().isEmpty());

   // Even full Flowable completion never creates an executable SQL ticket.
   assertEquals(0L,db.queryForObject("""
     SELECT count(*) FROM dg_execution_task WHERE org_id=?
     """,Long.class,org));
   assertEquals("NOT_SCHEDULED",db.queryForObject("""
     SELECT execution_status FROM dg_sql_approval WHERE org_id=? AND id=?
     """,String.class,org,approved));
   assertEquals(2L,db.queryForObject("""
     SELECT count(*) FROM dg_system_event WHERE org_id=?
       AND operation='SQL_APPROVAL_STAGE_APPROVED'
     """,Long.class,org));

   auth.logout(one);
   assertNull(resolveBearer(one.getTokenValue()),
     "Revoked native session must be unusable for further review");
  } finally {engine.close();}
 }
 private void seed() throws Exception {
  code="real_"+org.toString().replace("-","");
  db.update("INSERT INTO dg_organization(id,code,name) VALUES(?,?,?)",org,code,"Disposable platform org");
  for(UUID id:List.of(owner,first,second,outsider)){
   db.update("""
    INSERT INTO dg_user(id,org_id,username,display_name,password_hash)
    VALUES(?,?,?,?,?)
    """,id,org,userName(id),"Disposable reviewer",NativeTokens.passwords().encode(PASSWORD));
  }
  UUID role1=UUID.randomUUID(),role2=UUID.randomUUID();
  db.update("INSERT INTO dg_role(id,org_id,code,name) VALUES(?,?,'db-owner','Owner reviewer')",role1,org);
  db.update("INSERT INTO dg_role(id,org_id,code,name) VALUES(?,?,'dba','DBA reviewer')",role2,org);
  db.update("INSERT INTO dg_user_role(org_id,user_id,role_id) VALUES(?,?,?)",org,first,role1);
  db.update("INSERT INTO dg_user_role(org_id,user_id,role_id) VALUES(?,?,?)",org,second,role2);
  db.update("INSERT INTO dg_agent(id,org_id,owner_user_id,code,name) VALUES(?,?,?,?,?)",
    agent,org,owner,"agent","Disposable Agent");
  db.update("""
    INSERT INTO dg_datasource(id,org_id,owner_user_id,code,name,db_type,
      environment,host,port,database_name,status)
    VALUES(?,?,?,'test_db','Disposable database','POSTGRESQL',
      'TEST','localhost',5432,'dataguard','ACTIVE')
    """,source,org,owner);
  String xml=SafeApprovalGraph.compile(new SafeApprovalGraph.Design(List.of(
    new SafeApprovalGraph.Step("db-owner","Owner review"),
    new SafeApprovalGraph.Step("dba","DBA review")),false));
  String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
    .digest(xml.getBytes(StandardCharsets.UTF_8)));
  db.update("INSERT INTO dg_workflow_template(id,org_id,code,name,created_by) VALUES(?,?,?,?,?)",
    template,org,"cert_review","Certified review",owner);
  db.update("""
    INSERT INTO dg_workflow_version(id,org_id,workflow_id,revision,bpmn_xml,
      bpmn_digest,flowable_definition_key,status)
    VALUES(?,?,?,1,?,?,'safeWorkflowV2','PUBLISHED')
    """,version,org,template,xml,digest);
 }
 private String userName(UUID id){return "real_"+id.toString().replace("-","");}
 private Jwt authenticate(UUID id)throws Exception {
  var result=auth.login(new NativeAuthController.Login(code,userName(id),PASSWORD));
  assertTrue(result.token().startsWith("dg_"));
  Jwt jwt=resolveBearer(result.token());
  assertNotNull(jwt,"Actual PostgreSQL native session must authenticate");
  assertEquals("local-user:"+id,jwt.getSubject());
  return jwt;
 }
 private Jwt resolveBearer(String raw)throws Exception {
  HttpServletRequest req=mock(HttpServletRequest.class);
  HttpServletResponse response=mock(HttpServletResponse.class);
  FilterChain chain=mock(FilterChain.class);
  when(req.getHeader("Authorization")).thenReturn("Bearer "+raw);
  when(req.getServletPath()).thenReturn("/api/v1/approvals/my-inbox");
  AtomicReference<Jwt> captured=new AtomicReference<>();
  doAnswer(inv->{
    var authentication=SecurityContextHolder.getContext().getAuthentication();
    if(authentication!=null && authentication.getPrincipal() instanceof Jwt jwt)
       captured.set(jwt);
    return null;
  }).when(chain).doFilter(any(),any());
  new NativeSessionFilter(db).doFilter(req,response,chain);
  return captured.get();
 }
 private UUID insertApproval(){
  UUID request=UUID.randomUUID(),approval=UUID.randomUUID();
  db.update("""
    INSERT INTO dg_sql_request(id,org_id,agent_id,datasource_id,tool_name,
      decision,request_status,risk_level,requester_user_id)
    VALUES(?,?,?,?,'submit_change','REQUIRE_APPROVAL','PENDING_APPROVAL','LOW',NULL)
    """,request,org,agent,source);
  db.update("""
    INSERT INTO dg_sql_approval(id,org_id,request_id,workflow_version_id,
      approval_status,execution_status,expires_at)
    VALUES(?,?,?,?,'PENDING','NOT_SCHEDULED',now()+interval '30 minutes')
    """,approval,org,request,version);
  db.update("""
    INSERT INTO dg_outbox_event(id,org_id,aggregate_type,aggregate_id,event_type,payload)
    VALUES(?,?,'SQL_APPROVAL',?,'APPROVAL_REQUESTED','{}'::jsonb)
    """,UUID.randomUUID(),org,approval);
  return approval;
 }
 private String started(UUID id){
  for(int n=0;n<18;n++){
   outbox.poll();
   List<String> ids=db.query("""
     SELECT process_instance_id FROM dg_sql_approval WHERE org_id=? AND id=?
     """,(rs,i)->rs.getString(1),org,id);
   if(ids.size()==1 && ids.get(0)!=null){
    awaitPublished(id,"APPROVAL_REQUESTED");
    return ids.get(0);
   }
  }
  fail("Outbox did not create certified Flowable instance");
  return null;
 }
 private void published(UUID id,int expectedSteps){
  for(int i=0;i<18;i++){
   outbox.poll();
   int published=db.queryForObject("""
      SELECT count(*) FROM dg_outbox_event
      WHERE org_id=? AND aggregate_id=?
        AND event_type='APPROVAL_STEP_APPROVED' AND state='PUBLISHED'
      """,Integer.class,org,id);
   if(published==expectedSteps)return;
  }
  fail("Expected Flowable review synchronization for "+expectedSteps+" stages");
 }
 private void awaitPublished(UUID id,String type){
  for(int i=0;i<18;i++){
   outbox.poll();
   int published=db.queryForObject("""
      SELECT count(*) FROM dg_outbox_event WHERE org_id=? AND aggregate_id=?
      AND event_type=? AND state='PUBLISHED'
      """,Integer.class,org,id,type);
   if(published>0)return;
  }
  fail("Expected Flowable outbox event "+type);
 }
 private String approvalStatus(UUID id){
  return db.queryForObject("SELECT approval_status FROM dg_sql_approval WHERE org_id=? AND id=?",
    String.class,org,id);
 }
}
