package io.github.mcpdataguard.workflow;

import java.util.UUID;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Flowable engine, actual deployed BPMN and task transitions.
 * Caller/tenant validation runs in the service, not in the web UI.
 * The workflow engine never receives SQL write capabilities.
 */
class ReviewProcessAuthorizationIntegrationTest {
 @Test void assignedReviewerAndTenantAreRequiredForEachWorkflowTransition(){
  var engine=ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
    .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
    .buildProcessEngine();
  try{
   engine.getRepositoryService().createDeployment()
      .addClasspathResource("processes/two-level-review.bpmn20.xml").deploy();
   var service=new ReviewProcessService(
      engine.getRuntimeService(),engine.getTaskService());
   String tenant=UUID.randomUUID().toString();
   String otherTenant=UUID.randomUUID().toString();
   String reviewerOne=UUID.randomUUID().toString();
   String reviewerTwo=UUID.randomUUID().toString();
   String outsider=UUID.randomUUID().toString();
   String businessKey=UUID.randomUUID().toString();
   String instance=service.start(businessKey,tenant);
   assertThrows(IllegalStateException.class,()->service.start(businessKey,tenant),
       "A second live workflow for the same approval must not be created");
   assertThrows(SecurityException.class,()->service.pending(instance,otherTenant));
   var first=service.pending(instance,tenant);
   assertEquals(1,first.size());
   assertEquals("ownerReview",first.get(0).stage());
   String firstTask=first.get(0).taskId();
   assertThrows(SecurityException.class,()->
      service.complete(firstTask,reviewerOne,tenant),
      "Unclaimed tasks cannot be completed");
   engine.getTaskService().claim(firstTask,reviewerOne);
   assertThrows(SecurityException.class,()->
      service.complete(firstTask,outsider,tenant));
   assertThrows(SecurityException.class,()->
      service.complete(firstTask,reviewerOne,otherTenant));
   service.complete(firstTask,reviewerOne,tenant);
   assertEquals(false,engine.getRuntimeService().getVariable(instance,"sqlExecutionEnabled"),
      "Approving a workflow step must not enable physical SQL writes");
   var second=service.pending(instance,tenant);
   assertEquals(1,second.size());
   assertEquals("dbaReview",second.get(0).stage());
   String secondTask=second.get(0).taskId();
   engine.getTaskService().claim(secondTask,reviewerTwo);
   assertThrows(SecurityException.class,()->
     service.reject(secondTask,reviewerOne,tenant,"not the current assignee"));
   assertThrows(SecurityException.class,()->
     service.reject(secondTask,reviewerTwo,otherTenant,"wrong organization"));
   assertThrows(IllegalArgumentException.class,()->
     service.reject(secondTask,reviewerTwo,tenant,"  "));
   assertEquals(1,service.pending(instance,tenant).size());
   service.reject(secondTask,reviewerTwo,tenant,"declined by actual DBA reviewer");
   assertTrue(engine.getRuntimeService().createProcessInstanceQuery()
      .processInstanceId(instance).list().isEmpty());
   assertThrows(SecurityException.class,()->service.pending(instance,otherTenant));
  }finally{engine.close();}
 }
}
