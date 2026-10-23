package io.github.mcpdataguard.workflow;

import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable PostgreSQL exercises durable timeout and 8-attempt dead-letter policy. */
class ApprovalRecoveryIntegrationTest {
 private record Fixture(UUID org,UUID approval,JdbcTemplate jdbc){}
 private static String url(){
  String value=System.getenv("DG_OUTBOX_E2E_URL");
  Assumptions.assumeTrue(value!=null && value.startsWith("jdbc:postgresql://127.0.0.1:5432/"));
  return value;
 }
 private Fixture fixture(boolean expired){
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(
    url()+"?currentSchema=platform","postgres","test-only"));
  UUID org=UUID.randomUUID(),user=UUID.randomUUID(),agent=UUID.randomUUID(),
    datasource=UUID.randomUUID(),template=UUID.randomUUID(),
    version=UUID.randomUUID(),request=UUID.randomUUID(),approval=UUID.randomUUID();
  db.update("INSERT INTO dg_organization(id,code,name) VALUES(?,?,?)",
    org,"recovery_"+org.toString().replace("-",""),"Recovery test");
  db.update("INSERT INTO dg_user(id,org_id,username,display_name) VALUES(?,?,?,?)",
    user,org,"reviewer_"+user.toString().replace("-",""),"Reviewer");
  db.update("INSERT INTO dg_agent(id,org_id,owner_user_id,code,name) VALUES(?,?,?,?,?)",
    agent,org,user,"agent","Test agent");
  db.update("""
    INSERT INTO dg_datasource(id,org_id,owner_user_id,code,name,db_type,
      environment,host,port,database_name,status)
    VALUES(?,?,?,?,?,'POSTGRESQL','TEST','localhost',5432,'dataguard','ACTIVE')
    """,datasource,org,user,"datasource","Test datasource");
  db.update("INSERT INTO dg_workflow_template(id,org_id,code,name,created_by) VALUES(?,?,?,?,?)",
    template,org,"approval","Approval",user);
  db.update("""
    INSERT INTO dg_workflow_version(id,org_id,workflow_id,revision,bpmn_xml,
      bpmn_digest,flowable_definition_key,status)
    VALUES(?,?,?,1,'test',?,'sqlTwoLevelReview','PUBLISHED')
    """,version,org,template,"0".repeat(64));
  db.update("""
    INSERT INTO dg_sql_request(id,org_id,agent_id,datasource_id,tool_name,
      decision,request_status)
    VALUES(?,?,?,?,'submit_change','REQUIRE_APPROVAL','PENDING_APPROVAL')
    """,request,org,agent,datasource);
  db.update("""
    INSERT INTO dg_sql_approval(id,org_id,request_id,workflow_version_id,
      approval_status,execution_status,expires_at)
    VALUES(?,?,?,?,'PENDING','NOT_SCHEDULED',
      now() + (? * interval '1 minute'))
    """,approval,org,request,version,expired?-2:60);
  return new Fixture(org,approval,db);
 }
 @Test void expiredApprovalCancelsExecutionAndEmitsDurableRejection(){
  Fixture f=fixture(true);
  new ApprovalTimeoutWorker(url(),"postgres","test-only").expire();
  assertEquals("EXPIRED",f.jdbc().queryForObject(
    "SELECT approval_status FROM dg_sql_approval WHERE id=?",String.class,f.approval()));
  assertEquals("CANCELLED",f.jdbc().queryForObject(
    "SELECT execution_status FROM dg_sql_approval WHERE id=?",String.class,f.approval()));
  assertEquals(1L,f.jdbc().queryForObject("""
    SELECT count(*) FROM dg_outbox_event
    WHERE org_id=? AND aggregate_id=? AND event_type='APPROVAL_REJECTED'
    """,Long.class,f.org(),f.approval()));
 }
 @Test void failedProcessStartBecomesAuditedDeadLetterAfterEightRetries(){
  Fixture f=fixture(false);
  UUID event=UUID.randomUUID();
  f.jdbc().update("""
    INSERT INTO dg_outbox_event(id,org_id,aggregate_type,aggregate_id,event_type,payload)
    VALUES(?,?,'SQL_APPROVAL',?,'APPROVAL_REQUESTED','{}'::jsonb)
    """,event,f.org(),f.approval());
  ProcessEngine engine=ProcessEngineConfiguration
    .createStandaloneInMemProcessEngineConfiguration()
    .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
    .buildProcessEngine();
  try{
   // Deliberately omit BPMN deployment: startup must fail and be retried.
   ApprovalOutboxWorker worker=new ApprovalOutboxWorker(url(),"postgres","test-only",
      new ReviewProcessService(engine.getRuntimeService(),engine.getTaskService()),
      engine.getRuntimeService(),engine.getTaskService());
   for(int i=1;i<=8;i++){
    worker.poll();
    assertEquals(i,f.jdbc().queryForObject(
      "SELECT attempts FROM dg_outbox_event WHERE id=?",Integer.class,event));
    if(i<8)f.jdbc().update("UPDATE dg_outbox_event SET available_at=now() WHERE id=?",event);
   }
   assertEquals("FAILED",f.jdbc().queryForObject(
     "SELECT state FROM dg_outbox_event WHERE id=?",String.class,event));
   assertEquals(1L,f.jdbc().queryForObject("""
     SELECT count(*) FROM dg_system_event
     WHERE org_id=? AND resource_id=? AND operation='APPROVAL_EVENT_DEAD_LETTER'
     """,Long.class,f.org(),event.toString()));
  }finally{engine.close();}
 }
}
