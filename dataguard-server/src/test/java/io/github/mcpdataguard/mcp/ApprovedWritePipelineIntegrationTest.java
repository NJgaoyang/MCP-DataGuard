package io.github.mcpdataguard.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Genuine disposable PostgreSQL platform + TLS target write integration.
 * Runs only with isolated test credentials supplied by the acceptance script.
 */
class ApprovedWritePipelineIntegrationTest {
 private static final UUID TARGET=UUID.fromString("91234567-1111-2222-3333-444455556666");
 private static final String UPDATE="UPDATE write_probe SET amount=? WHERE id=?";
 private static String url(){
  var url=System.getenv("DG_OUTBOX_E2E_URL");
  Assumptions.assumeTrue(url!=null && url.startsWith("jdbc:postgresql://127.0.0.1:5432/"));
  Assumptions.assumeTrue("true".equals(System.getenv("DG_WRITE_JDBC_E2E")));
  return url+"?currentSchema=platform";
 }
 private record Fixture(JdbcTemplate db,UUID org,UUID agent,UUID owner,UUID first,
                        UUID second,UUID workflowVersion,ApprovalPayloadCrypto crypto,
                        ExecutionTicketSigner signer,ApprovedExecutionQueue queue,
                        CertifiedWriteWorker worker){}
 private static Fixture create(){
  var data=new DriverManagerDataSource(url(),"postgres","test-only");
  var jdbc=new JdbcTemplate(data);
  var org=UUID.randomUUID();
  var owner=UUID.randomUUID();var first=UUID.randomUUID();var second=UUID.randomUUID();
  var agent=UUID.randomUUID();var workflow=UUID.randomUUID();var ver=UUID.randomUUID();
  String orgCode="write_"+org.toString().replace("-","");
  jdbc.update("INSERT INTO dg_organization(id,code,name) VALUES(?,?,?)",
    org,orgCode,"Write test");
  for(var u:List.of(owner,first,second))
   jdbc.update("INSERT INTO dg_user(id,org_id,username,display_name) VALUES(?,?,?,?)",
     u,org,"user_"+u.toString().replace("-",""),"Write reviewer");
  jdbc.update("INSERT INTO dg_agent(id,org_id,owner_user_id,code,name) VALUES(?,?,?,?,?)",
     agent,org,owner,"isolated_agent","Isolated agent");
  jdbc.update("""
    INSERT INTO dg_datasource(id,org_id,owner_user_id,code,name,db_type,
      environment,host,port,database_name,tls_mode,status)
    VALUES(?,?,?,'write_target','Isolated write target','POSTGRESQL',
      'TEST','localhost',5432,'dataguard','VERIFY_FULL','ACTIVE')
    """,TARGET,org,owner);
  jdbc.update("""
    INSERT INTO dg_datasource_capability(org_id,datasource_id,capability,
      support_status,plugin_version,certified_at,evidence_ref)
    VALUES(?,?,'WRITE_EXECUTION','CERTIFIED','test-v1',now(),'isolated:pg17:tls:role')
    """,org,TARGET);
  jdbc.update("""
    INSERT INTO dg_datasource_secret(id,org_id,datasource_id,purpose,provider,secret_ref)
    VALUES(?,?,?,'WRITE','ENV',?)
    """,UUID.randomUUID(),org,TARGET,
      "DG_TARGET_"+TARGET.toString().replace("-","").toUpperCase()+"_WRITE");
  jdbc.update("INSERT INTO dg_workflow_template(id,org_id,code,name,created_by) VALUES(?,?,?,?,?)",
    workflow,org,"sql_write","Isolated approval",owner);
  String legacyDefinition="certified test definition";
  String legacyDigest;
  try{legacyDigest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
     .digest(legacyDefinition.getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
  jdbc.update("""
    INSERT INTO dg_workflow_version(id,org_id,workflow_id,revision,bpmn_xml,
      bpmn_digest,flowable_definition_key,status)
    VALUES(?,?,?,1,?,?,'sqlTwoLevelReview','PUBLISHED')
    """,ver,org,workflow,legacyDefinition,legacyDigest);
  var crypto=new ApprovalPayloadCrypto(System.getenv("DG_APPROVAL_MASTER_KEY_BASE64"));
  var signer=new ExecutionTicketSigner(System.getenv("DG_EXECUTION_SIGNING_KEY_BASE64"));
  var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
  var queue=new ApprovedExecutionQueue(jdbc,new ExecutionEligibilityService(jdbc),signer,true);
  var worker=new CertifiedWriteWorker(jdbc,tx,signer,crypto,
      new CertifiedWriteJdbcExecutor("localhost"),true);
  return new Fixture(jdbc,org,agent,owner,first,second,ver,crypto,signer,queue,worker);
 }
 private static UUID approval(Fixture f,int id){
  UUID request=UUID.randomUUID(),approval=UUID.randomUUID();
  var payload=f.crypto().seal(UPDATE,List.of(625,id),"isolated approval");
  String digest;
  try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
    .digest(UPDATE.getBytes(StandardCharsets.UTF_8)));}
  catch(Exception ex){throw new IllegalStateException(ex);}
  f.db().update("""
    INSERT INTO dg_sql_request
      (id,org_id,agent_id,datasource_id,tool_name,database_name,operation,
       sql_fingerprint,decision,request_status)
    VALUES(?,?,?,?,'submit_change','dataguard','UPDATE',?,
       'REQUIRE_APPROVAL','PENDING_APPROVAL')
    """,request,f.org(),f.agent(),TARGET,digest);
  f.db().update("""
    INSERT INTO dg_sql_request_payload(org_id,request_id,encrypted_sql,
      encrypted_parameters,encryption_key_ref,encryption_algorithm,canonical_payload_hmac)
    VALUES(?,?,?,?,?,'AES-256-GCM',?)
    """,f.org(),request,payload.encryptedSql(),payload.encryptedParams(),
      ApprovalPayloadCrypto.KEY_REF,payload.hmac());
  f.db().update("""
    INSERT INTO dg_sql_approval(id,org_id,request_id,workflow_version_id,
       process_instance_id,approval_status,execution_status,expires_at)
    VALUES(?,?,?,?,?,'APPROVED','NOT_SCHEDULED',now()+interval '1 hour')
    """,approval,f.org(),request,f.workflowVersion(),UUID.randomUUID().toString());
  for(UUID reviewer:List.of(f.first(),f.second())){
   f.db().update("""
     INSERT INTO dg_sql_approval_action(id,org_id,approval_id,reviewer_user_id,
       flowable_task_id,action,comment_text)
     VALUES(?,?,?,? ,?,'APPROVE','test approval')
     """,UUID.randomUUID(),f.org(),approval,reviewer,UUID.randomUUID().toString());
  }
  for(String event:List.of("APPROVAL_REQUESTED","APPROVAL_STEP_APPROVED","APPROVAL_STEP_APPROVED")){
   f.db().update("""
     INSERT INTO dg_outbox_event
      (id,org_id,aggregate_type,aggregate_id,event_type,payload,state,dispatched_at)
     VALUES(?,?,'SQL_APPROVAL',?,?,'{}'::jsonb,'PUBLISHED',now())
     """,UUID.randomUUID(),f.org(),approval,event);
  }
  return approval;
 }
 private static ApprovedExecutionQueue.QueueResult release(Fixture f,UUID approval){
  var row=f.db().queryForMap("""
    SELECT r.sql_fingerprint,p.canonical_payload_hmac FROM dg_sql_approval a
    JOIN dg_sql_request r ON r.org_id=a.org_id AND r.id=a.request_id
    JOIN dg_sql_request_payload p ON p.org_id=r.org_id AND p.request_id=r.id
    WHERE a.id=?
    """,approval);
  return f.queue().enqueue(f.org(),approval,
    (String)row.get("sql_fingerprint"),(String)row.get("canonical_payload_hmac"));
 }
 /**
  * An ACTUAL Java CertifiedWriteWorker runs in a separate JVM. Its test-only
  * JdbcExecutor subclass pauses after super.execute has committed to the real
  * PostgreSQL target, but before the platform can acknowledge success. Parent
  * SIGKILLs JVM, expires its durable IN_FLIGHT lease, then attempts a restart.
  * The transaction remains committed exactly once; task/attempt remain UNKNOWN.
  */
 private void javaWorkerSigkillAfterTargetCommitIsNeverReplayed(Fixture f) throws Exception {
  int row=100000+java.util.concurrent.ThreadLocalRandom.current().nextInt(500000);
  f.db().update("INSERT INTO public.write_probe(id,amount) VALUES(?,100)",row);
  UUID approved=approval(f,row);
  var queued=release(f,approved);
  assertTrue(queued.queued());
  String javaBinary=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
  String testClasspath=System.getProperty("java.class.path");
  var command=new ProcessBuilder(javaBinary,"-cp",testClasspath,
       CrashAfterRemoteCommit.class.getName());
  command.redirectErrorStream(true);
  var log=java.nio.file.Files.createTempFile("dg-isolated-real-worker-",".log");
  command.redirectOutput(log.toFile());
  Process workerProcess=null;
  try {
   workerProcess=command.start();
   boolean committed=false;
   for(int i=0;i<120;i++){
    int amount=f.db().queryForObject(
      "SELECT amount FROM public.write_probe WHERE id=?",Integer.class,row);
    if(amount==625){committed=true;break;}
    if(!workerProcess.isAlive())break;
    Thread.sleep(100);
   }
   assertTrue(committed,"Java Worker must commit to PostgreSQL before SIGKILL");
   assertTrue(workerProcess.isAlive(),"Worker must still await platform ACK");
   assertEquals("EXECUTING",f.db().queryForObject(
      "SELECT task_state FROM dg_execution_task WHERE id=?",
      String.class,queued.taskId()));
   workerProcess.destroyForcibly();
   assertTrue(workerProcess.waitFor(7,java.util.concurrent.TimeUnit.SECONDS),
     "Killed Java Worker must terminate");
   assertNotEquals(0,workerProcess.exitValue(),"Process must die without ACK");

   f.db().update("""
     UPDATE dg_execution_task SET lease_until=now()-interval '1 minute'
     WHERE org_id=? AND id=? AND task_state='EXECUTING'
     """,f.org(),queued.taskId());
   new ExecutionRecoveryWatchdog(f.db(),true).quarantineExpiredLeases();
   assertEquals("UNKNOWN",f.db().queryForObject(
      "SELECT task_state FROM dg_execution_task WHERE id=?",
      String.class,queued.taskId()));
   assertEquals("UNKNOWN",f.db().queryForObject(
      "SELECT execution_status FROM dg_sql_approval WHERE id=?",
      String.class,approved));
   assertEquals(1,f.db().queryForObject(
      "SELECT attempt_no FROM dg_execution_task WHERE id=?",
      Integer.class,queued.taskId()));
   assertEquals("UNKNOWN",f.db().queryForObject(
      "SELECT phase FROM dg_execution_attempt WHERE task_id=?",
      String.class,queued.taskId()));
   assertEquals(1L,f.db().queryForObject("""
       SELECT count(*) FROM dg_alert_event e JOIN dg_alert_rule r
         ON r.org_id=e.org_id AND r.id=e.rule_id
       WHERE e.org_id=? AND r.code='WRITE_EXECUTION_UNKNOWN'
         AND e.event_state='OPEN' AND e.details->>'taskId'=?
     """,Long.class,f.org(),queued.taskId().toString()));
   // Real Java Worker restart with valid credentials must NOT re-claim UNKNOWN.
   f.worker().runOne();
   assertEquals(1L,f.db().queryForObject(
      "SELECT count(*) FROM dg_execution_attempt WHERE task_id=?",
      Long.class,queued.taskId()));
   assertEquals(625,f.db().queryForObject(
      "SELECT amount FROM public.write_probe WHERE id=?",Integer.class,row));
   assertEquals(0L,f.db().queryForObject("""
     SELECT count(*) FROM dg_execution_task
     WHERE org_id=? AND id=? AND task_state IN ('QUEUED','EXECUTING')
     """,Long.class,f.org(),queued.taskId()));
  } finally {
   if(workerProcess!=null && workerProcess.isAlive())workerProcess.destroyForcibly();
   java.nio.file.Files.deleteIfExists(log);
  }
 }

 /** Executed ONLY as a separate, disposable Maven test subprocess. */
 public static final class CrashAfterRemoteCommit {
  public static void main(String[] ignored) throws Exception {
   var url=System.getenv("DG_OUTBOX_E2E_URL");
   if(url==null || !url.startsWith("jdbc:postgresql://127.0.0.1:5432/dataguard")
       || !"true".equals(System.getenv("DG_WRITE_JDBC_E2E")))
    throw new SecurityException("Isolated PostgreSQL acceptance environment required");
   var source=new DriverManagerDataSource(url+"?currentSchema=platform","postgres","test-only");
   var jdbc=new JdbcTemplate(source);
   var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
   var crypto=new ApprovalPayloadCrypto(System.getenv("DG_APPROVAL_MASTER_KEY_BASE64"));
   var signer=new ExecutionTicketSigner(System.getenv("DG_EXECUTION_SIGNING_KEY_BASE64"));
   var executor=new CertifiedWriteJdbcExecutor("localhost"){
    @Override public Result execute(Target target,String sql,List<Object> values)throws Exception{
     Result committed=super.execute(target,sql,values);
     System.out.println("SYNTHETIC_TARGET_COMMIT_DURABLE");
     System.out.flush();
     Thread.sleep(60_000); // Parent must SIGKILL before platform ACK.
     return committed;
    }
   };
   new CertifiedWriteWorker(jdbc,tx,signer,crypto,executor,true).runOne();
  }
 }
 @Test void singleUseQueueThenPhysicalCommitAndNoSecondWrite() throws Exception {
  Fixture f=create();
  UUID approved=approval(f,1);
  var task=release(f,approved);
  assertTrue(task.queued());
  assertFalse(release(f,approved).queued(),"One approval must not queue twice");
  f.worker().runOne();
  assertEquals("SUCCEEDED",f.db().queryForObject(
    "SELECT task_state FROM dg_execution_task WHERE id=?",String.class,task.taskId()));
  assertEquals(1,f.db().queryForObject(
    "SELECT count(*) FROM dg_execution_attempt WHERE task_id=?",Integer.class,task.taskId()));
  assertEquals(625,f.db().queryForObject(
    "SELECT amount FROM public.write_probe WHERE id=1",Integer.class));
  f.worker().runOne();
  assertEquals(1,f.db().queryForObject(
    "SELECT count(*) FROM dg_execution_attempt WHERE task_id=?",Integer.class,task.taskId()));
  // A new one-time ticket with a nonexistent row cannot be retried after its
  // remote outcome becomes uncertain.
  var unsuccessfulApproval=approval(f,99999);
  var unsuccessfulTask=release(f,unsuccessfulApproval);
  assertTrue(unsuccessfulTask.queued());
  f.worker().runOne();
  assertEquals("UNKNOWN",f.db().queryForObject(
    "SELECT task_state FROM dg_execution_task WHERE id=?",String.class,unsuccessfulTask.taskId()));
  f.worker().runOne();
  assertEquals(1,f.db().queryForObject(
    "SELECT count(*) FROM dg_execution_attempt WHERE task_id=?",Integer.class,unsuccessfulTask.taskId()));
  javaWorkerSigkillAfterTargetCommitIsNeverReplayed(f);
 }
}
