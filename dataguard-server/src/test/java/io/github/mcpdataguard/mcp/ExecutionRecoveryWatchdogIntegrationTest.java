package io.github.mcpdataguard.mcp;

import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real isolated PostgreSQL fault test: a lost write lease has an indeterminate
 * remote commit and must be quarantined, alerted and never silently replayed.
 */
class ExecutionRecoveryWatchdogIntegrationTest {
 @Test void expiredWriteBecomesUnknownWithOneCriticalAlertAndNoReplay(){
  String url=System.getenv("DG_OUTBOX_E2E_URL");
  Assumptions.assumeTrue(url!=null &&
    url.startsWith("jdbc:postgresql://127.0.0.1:5432/dataguard"));
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(
    url+"?currentSchema=platform","postgres","test-only"));
  UUID org=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),
     datasource=UUID.randomUUID(),workflow=UUID.randomUUID(),version=UUID.randomUUID(),
     request=UUID.randomUUID(),approval=UUID.randomUUID(),task=UUID.randomUUID();
  db.update("INSERT INTO dg_organization(id,code,name) VALUES(?,?,?)",
     org,"unknown_"+org.toString().replace("-",""),"Write recovery test");
  db.update("INSERT INTO dg_user(id,org_id,username,display_name) VALUES(?,?,?,?)",
     owner,org,"owner","Recovery owner");
  db.update("INSERT INTO dg_agent(id,org_id,owner_user_id,code,name) VALUES(?,?,?,?,?)",
     agent,org,owner,"agent","Agent");
  db.update("""
    INSERT INTO dg_datasource(id,org_id,owner_user_id,code,name,db_type,
      environment,host,port,database_name,status)
    VALUES(?,?,?,'source','Recovery datasource','POSTGRESQL',
      'TEST','localhost',5432,'dataguard','ACTIVE')
    """,datasource,org,owner);
  db.update("""
    INSERT INTO dg_workflow_template(id,org_id,code,name,created_by)
    VALUES(?,?,'two_review','Two-stage review',?)
    """,workflow,org,owner);
  db.update("""
    INSERT INTO dg_workflow_version(id,org_id,workflow_id,revision,bpmn_xml,
      bpmn_digest,flowable_definition_key,status)
    VALUES (?,?,?,1,'isolation',?,'sqlTwoLevelReview','PUBLISHED')
    """,version,org,workflow,"0".repeat(64));
  db.update("""
    INSERT INTO dg_sql_request(id,org_id,agent_id,datasource_id,tool_name,
      decision,request_status)
    VALUES(?,?,?,?,'submit_change','REQUIRE_APPROVAL','PENDING_APPROVAL')
    """,request,org,agent,datasource);
  db.update("""
    INSERT INTO dg_sql_approval(id,org_id,request_id,workflow_version_id,
      approval_status,execution_status,expires_at)
    VALUES (?,?,?,?,'APPROVED','EXECUTING',now()+interval '1 hour')
    """,approval,org,request,version);
  db.update("""
    INSERT INTO dg_execution_task(id,org_id,approval_id,task_state,
      payload_hmac,authorization_digest,authorization_expires_at,
      lease_owner,lease_until,attempt_no)
    VALUES (?,? ,?,'EXECUTING',?,?,now()+interval '5 minutes',
      'lost-worker',now()-interval '2 minutes',1)
    """,task,org,approval,"a".repeat(64),"b".repeat(64));
  // Fault injection boundary: physical target COMMIT completes, but the
  // simulated worker crashes before persisting the platform acknowledgement.
  // This tests the most dangerous state: write DID occur yet the ticket
  // remains EXECUTING with a lost/expired lease.
  db.execute("""
    CREATE TABLE IF NOT EXISTS public.write_chaos_probe(
      task_id UUID PRIMARY KEY, amount INTEGER NOT NULL)
    """);
  db.update("INSERT INTO public.write_chaos_probe(task_id,amount) VALUES (?,100)",
    task);
  var tx=new org.springframework.transaction.support.TransactionTemplate(
    new org.springframework.jdbc.datasource.DataSourceTransactionManager(
      db.getDataSource()));
  tx.executeWithoutResult(status->db.update("""
    UPDATE public.write_chaos_probe SET amount=625 WHERE task_id=?
    """,task));
  assertEquals(625,db.queryForObject("""
    SELECT amount FROM public.write_chaos_probe WHERE task_id=?
    """,Integer.class,task),"The target transaction has physically committed");
  var watchdog=new ExecutionRecoveryWatchdog(db,true);
  watchdog.quarantineExpiredLeases();
  assertEquals("UNKNOWN",db.queryForObject("""
    SELECT task_state FROM dg_execution_task WHERE org_id=? AND id=?
    """,String.class,org,task));
  assertEquals("UNKNOWN",db.queryForObject("""
    SELECT execution_status FROM dg_sql_approval WHERE org_id=? AND id=?
    """,String.class,org,approval));
  assertEquals(1,db.queryForObject("""
    SELECT attempt_no FROM dg_execution_task WHERE org_id=? AND id=?
    """,Integer.class,org,task));
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_alert_event a JOIN dg_alert_rule r
       ON a.org_id=r.org_id AND a.rule_id=r.id
    WHERE a.org_id=? AND r.code='WRITE_EXECUTION_UNKNOWN'
      AND a.event_state='OPEN'
    """,Long.class,org));
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_system_event WHERE org_id=?
     AND operation='WRITE_RESULT_UNKNOWN'
    """,Long.class,org));
  // A second poll must not mutate UNKNOWN or automatically submit another attempt.
  watchdog.quarantineExpiredLeases();
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_alert_event WHERE org_id=?
    """,Long.class,org));
  assertEquals(1,db.queryForObject("""
    SELECT attempt_no FROM dg_execution_task WHERE org_id=? AND id=?
    """,Integer.class,org,task));
  assertEquals(625,db.queryForObject("""
    SELECT amount FROM public.write_chaos_probe WHERE task_id=?
    """,Integer.class,task),
    "Uncertain committed write must not be automatically replayed or reverted");
 }
 @Test void disabledWatchdogNeverQueriesExecutionTasks(){
  JdbcTemplate db=org.mockito.Mockito.mock(JdbcTemplate.class);
  new ExecutionRecoveryWatchdog(db,false).quarantineExpiredLeases();
  org.mockito.Mockito.verifyNoInteractions(db);
 }
}
