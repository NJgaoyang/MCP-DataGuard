package io.github.mcpdataguard.workflow;

import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReviewProcessIntegrationTest {
 @Test void twoIndependentReviewersCompleteActualBpmnWithoutSqlExecution(){
  ProcessEngine engine=ProcessEngineConfiguration
    .createStandaloneInMemProcessEngineConfiguration()
    .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
    .buildProcessEngine();
  try{
   engine.getRepositoryService().createDeployment()
     .addClasspathResource("processes/two-level-review.bpmn20.xml").deploy();
   ReviewProcessService service=new ReviewProcessService(
      engine.getRuntimeService(),engine.getTaskService());
   String org=UUID.randomUUID().toString();
   String id=service.start(UUID.randomUUID().toString(),org);
   var first=service.pending(id,org);
   assertEquals(1,first.size());
   assertEquals("ownerReview",first.get(0).stage());
   assertThrows(SecurityException.class,()->service.pending(id,UUID.randomUUID().toString()));
   String owner=UUID.randomUUID().toString();
   assertThrows(SecurityException.class,()->service.complete(first.get(0).taskId(),owner,org));
   engine.getTaskService().claim(first.get(0).taskId(),owner);
   service.complete(first.get(0).taskId(),owner,org);
   var second=service.pending(id,org);
   assertEquals(1,second.size());
   assertEquals("dbaReview",second.get(0).stage());
   String dba=UUID.randomUUID().toString();
   engine.getTaskService().claim(second.get(0).taskId(),dba);
   service.complete(second.get(0).taskId(),dba,org);
   assertTrue(engine.getRuntimeService().createProcessInstanceQuery()
      .processInstanceId(id).list().isEmpty());
  }finally{engine.close();}
 }
 @Test void rejectionEndsLiveProcess(){
  ProcessEngine engine=ProcessEngineConfiguration
    .createStandaloneInMemProcessEngineConfiguration()
    .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
    .buildProcessEngine();
  try{
   engine.getRepositoryService().createDeployment()
      .addClasspathResource("processes/two-level-review.bpmn20.xml").deploy();
   ReviewProcessService service=new ReviewProcessService(
      engine.getRuntimeService(),engine.getTaskService());
   String org=UUID.randomUUID().toString();
   String id=service.start(UUID.randomUUID().toString(),org);
   String task=service.pending(id,org).get(0).taskId();
   String reviewer=UUID.randomUUID().toString();
   engine.getTaskService().claim(task,reviewer);
   assertThrows(SecurityException.class,()->
     service.reject(task,reviewer,UUID.randomUUID().toString(),"risky"));
   service.reject(task,reviewer,org,"risk unacceptable");
   assertTrue(engine.getRuntimeService().createProcessInstanceQuery()
      .processInstanceId(id).list().isEmpty());
  }finally{engine.close();}
 }
}
