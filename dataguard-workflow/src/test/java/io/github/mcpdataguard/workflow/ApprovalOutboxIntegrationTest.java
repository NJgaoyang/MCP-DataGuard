package io.github.mcpdataguard.workflow;

import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit opt-in PostgreSQL + live Flowable cross-database event test.
 * Runs only inside an isolated ephemeral PostgreSQL container network.
 */
class ApprovalOutboxIntegrationTest {
 @Test void platformOutboxStartsAndCompletesTwoLevelProcess() throws Exception {
  String url=System.getenv("DG_OUTBOX_E2E_URL");
  Assumptions.assumeTrue(url!=null && url.startsWith("jdbc:postgresql://127.0.0.1:5432/"));
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(url,"postgres","test-only"));
  UUID org=UUID.randomUUID(),owner=UUID.randomUUID(),first=UUID.randomUUID(),
    second=UUID.randomUUID(),agent=UUID.randomUUID(),source=UUID.randomUUID(),
    workflowTemplate=UUID.randomUUID(),workflowVersion=UUID.randomUUID(),
    request=UUID.randomUUID(),approval=UUID.randomUUID(),event=UUID.randomUUID();
  String orgCode="e2e_"+org.toString().replace("-","");
  db.update("INSERT INTO platform.dg_organization(id,code,name) VALUES(?,?,?)",
    org,orgCode,"E2E isolated org");
  for(UUID user:new UUID[]{owner,first,second})
   db.update("INSERT INTO platform.dg_user(id,org_id,username,display_name) VALUES(?,?,?,?)",
     user,org,"user_"+user.toString().replace("-",""),"E2E reviewer");
  db.update("""
    INSERT INTO platform.dg_agent(id,org_id,owner_user_id,code,name)
    VALUES(?,?,?,?,?)
    """,agent,org,owner,"e2e_agent","E2E Agent");
  db.update("""
    INSERT INTO platform.dg_datasource
      (id,org_id,owner_user_id,code,name,db_type,environment,host,port,database_name,status)
    VALUES(?,?,?,?,?,'POSTGRESQL','TEST','localhost',5432,'dataguard','ACTIVE')
    """,source,org,owner,"e2e_db","E2E database");
  db.update("""
    INSERT INTO platform.dg_workflow_template(id,org_id,code,name,created_by)
    VALUES(?,?,?,?,?)
    """,workflowTemplate,org,"two_stage_review","Two-stage review",owner);
  db.update("""
    INSERT INTO platform.dg_workflow_version
      (id,org_id,workflow_id,revision,bpmn_xml,bpmn_digest,flowable_definition_key,status)
    VALUES(?,?,?,1,'test fixture',?,'sqlTwoLevelReview','PUBLISHED')
    """,workflowVersion,org,workflowTemplate,"0".repeat(64));
  db.update("""
    INSERT INTO platform.dg_sql_request
      (id,org_id,agent_id,datasource_id,tool_name,decision,request_status)
    VALUES(?,?,?,?,'submit_change','REQUIRE_APPROVAL','PENDING_APPROVAL')
    """,request,org,agent,source);
  db.update("""
    INSERT INTO platform.dg_sql_approval
      (id,org_id,request_id,workflow_version_id,approval_status,execution_status,expires_at)
    VALUES(?,?,?,?,'PENDING','NOT_SCHEDULED',now()+interval '1 day')
    """,approval,org,request,workflowVersion);
  db.update("""
    INSERT INTO platform.dg_outbox_event
      (id,org_id,aggregate_type,aggregate_id,event_type,payload)
    VALUES(?,?,'SQL_APPROVAL',?,'APPROVAL_REQUESTED','{}'::jsonb)
    """,event,org,approval);
  ProcessEngine engine=ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
   .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
   .buildProcessEngine();
  try{
   engine.getRepositoryService().createDeployment()
     .addClasspathResource("processes/two-level-review.bpmn20.xml").deploy();
   ReviewProcessService service=new ReviewProcessService(
     engine.getRuntimeService(),engine.getTaskService());
   ApprovalOutboxWorker worker=new ApprovalOutboxWorker(url,"postgres","test-only",
     service,engine.getRuntimeService(),engine.getTaskService());
   worker.poll();
   String instance=db.queryForObject("""
     SELECT process_instance_id FROM platform.dg_sql_approval WHERE org_id=? AND id=?
     """,String.class,org,approval);
   assertNotNull(instance,"Worker must start a Flowable instance");
   assertEquals("PUBLISHED",db.queryForObject(
     "SELECT state FROM platform.dg_outbox_event WHERE id=?",String.class,event));
   assertEquals("ownerReview",service.pending(instance,org.toString()).get(0).stage());
   submitStage(db,org,approval,first,1);
   worker.poll();
   assertEquals("dbaReview",service.pending(instance,org.toString()).get(0).stage());
   submitStage(db,org,approval,second,2);
   worker.poll();
   assertTrue(engine.getRuntimeService().createProcessInstanceQuery()
     .processInstanceId(instance).list().isEmpty());
   assertEquals("NOT_SCHEDULED",db.queryForObject("""
      SELECT execution_status FROM platform.dg_sql_approval WHERE id=?
      """,String.class,approval),"Approval may not trigger SQL execution");
   // Exercise the opt-in runtime path against the same real platform outbox
   // rather than merely testing the BPMN deployment component in isolation.
   String certifiedXml;
   try(var stream=getClass().getClassLoader().getResourceAsStream(
       "processes/two-level-review.bpmn20.xml")){
    assertNotNull(stream);
    certifiedXml=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   }
   String certifiedDigest=java.util.HexFormat.of().formatHex(
      java.security.MessageDigest.getInstance("SHA-256")
        .digest(certifiedXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   db.update("""
     UPDATE platform.dg_workflow_version SET bpmn_xml=?,bpmn_digest=?
     WHERE org_id=? AND id=? AND status='PUBLISHED'
     """,certifiedXml,certifiedDigest,org,workflowVersion);
   UUID nextRequest=UUID.randomUUID(),nextApproval=UUID.randomUUID(),nextEvent=UUID.randomUUID();
   db.update("""
     INSERT INTO platform.dg_sql_request
       (id,org_id,agent_id,datasource_id,tool_name,decision,request_status)
     VALUES(?,?,?,?,'submit_change','REQUIRE_APPROVAL','PENDING_APPROVAL')
     """,nextRequest,org,agent,source);
   db.update("""
     INSERT INTO platform.dg_sql_approval
       (id,org_id,request_id,workflow_version_id,approval_status,execution_status,expires_at)
     VALUES(?,?,?,?,'PENDING','NOT_SCHEDULED',now()+interval '1 day')
     """,nextApproval,org,nextRequest,workflowVersion);
   db.update("""
     INSERT INTO platform.dg_outbox_event
       (id,org_id,aggregate_type,aggregate_id,event_type,payload)
     VALUES(?,?,'SQL_APPROVAL',?,'APPROVAL_REQUESTED','{}'::jsonb)
     """,nextEvent,org,nextApproval);
   var dynamic=new CertifiedWorkflowDeployment(engine.getRepositoryService(),
     engine.getRuntimeService(),true);
   var dynamicWorker=new ApprovalOutboxWorker(url,"postgres","test-only",
      service,engine.getRuntimeService(),engine.getTaskService(),dynamic);
   dynamicWorker.poll();
   String dynamicInstance=db.queryForObject("""
      SELECT process_instance_id FROM platform.dg_sql_approval WHERE org_id=? AND id=?
      """,String.class,org,nextApproval);
   assertNotNull(dynamicInstance,"Outbox must start dynamically certified BPMN");
   assertEquals("ownerReview",service.pending(dynamicInstance,org.toString()).get(0).stage());
   submitStage(db,org,nextApproval,first,1);
   dynamicWorker.poll();
   assertEquals("dbaReview",service.pending(dynamicInstance,org.toString()).get(0).stage());
   submitStage(db,org,nextApproval,second,2);
   dynamicWorker.poll();
   assertTrue(engine.getRuntimeService().createProcessInstanceQuery()
      .processInstanceId(dynamicInstance).list().isEmpty());
   assertEquals("PUBLISHED",db.queryForObject(
      "SELECT state FROM platform.dg_outbox_event WHERE id=?",String.class,nextEvent));
   assertEquals("NOT_SCHEDULED",db.queryForObject("""
      SELECT execution_status FROM platform.dg_sql_approval WHERE id=?
      """,String.class,nextApproval));
   assertEquals(0L,db.queryForObject("""
     SELECT count(*) FROM platform.dg_outbox_event
      WHERE org_id=? AND state='PENDING'
     """,Long.class,org));
   // A published definition whose bytes no longer match its immutable digest
   // must be rejected before creating a third process instance.
   UUID changedRequest=UUID.randomUUID(),changedApproval=UUID.randomUUID(),
     changedEvent=UUID.randomUUID();
   db.update("""
     INSERT INTO platform.dg_sql_request
       (id,org_id,agent_id,datasource_id,tool_name,decision,request_status)
     VALUES(?,?,?,?,'submit_change','REQUIRE_APPROVAL','PENDING_APPROVAL')
     """,changedRequest,org,agent,source);
   db.update("""
     INSERT INTO platform.dg_sql_approval
       (id,org_id,request_id,workflow_version_id,approval_status,execution_status,expires_at)
     VALUES(?,?,?,?,'PENDING','NOT_SCHEDULED',now()+interval '1 day')
     """,changedApproval,org,changedRequest,workflowVersion);
   db.update("""
     INSERT INTO platform.dg_outbox_event
       (id,org_id,aggregate_type,aggregate_id,event_type,payload)
     VALUES(?,?,'SQL_APPROVAL',?,'APPROVAL_REQUESTED','{}'::jsonb)
     """,changedEvent,org,changedApproval);
   db.update("""
     UPDATE platform.dg_workflow_version SET bpmn_digest=?
     WHERE org_id=? AND id=?
     ""","0".repeat(64),org,workflowVersion);
   dynamicWorker.poll();
   assertNull(db.queryForObject("""
      SELECT process_instance_id FROM platform.dg_sql_approval WHERE id=?
      """,String.class,changedApproval));
   assertEquals(1,db.queryForObject(
     "SELECT attempts FROM platform.dg_outbox_event WHERE id=?",Integer.class,changedEvent));
   assertEquals("PENDING",db.queryForObject(
     "SELECT state FROM platform.dg_outbox_event WHERE id=?",String.class,changedEvent));

  }finally{engine.close();}
 }
 private static void submitStage(JdbcTemplate db,UUID org,UUID approval,UUID reviewer,int stage){
  UUID action=UUID.randomUUID();
  db.update("""
    INSERT INTO platform.dg_sql_approval_action
      (id,org_id,approval_id,reviewer_user_id,flowable_task_id,action,comment_text)
    VALUES(?,?,?,?,'platform:awaiting-flowable','APPROVE','approved in isolated test')
    """,action,org,approval,reviewer);
  String json="{\"actionId\":\""+action+"\",\"reviewerId\":\""+reviewer+
    "\",\"stage\":"+stage+"}";
  db.update("""
    INSERT INTO platform.dg_outbox_event
      (id,org_id,aggregate_type,aggregate_id,event_type,payload)
    VALUES(?,?,'SQL_APPROVAL',?,'APPROVAL_STEP_APPROVED',CAST(? AS jsonb))
    """,UUID.randomUUID(),org,approval,json);
 }
}
