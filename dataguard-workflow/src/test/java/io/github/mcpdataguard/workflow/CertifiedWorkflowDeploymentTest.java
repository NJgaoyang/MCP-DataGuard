package io.github.mcpdataguard.workflow;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CertifiedWorkflowDeploymentTest {
 private static String template()throws Exception{
  try(InputStream in=CertifiedWorkflowDeploymentTest.class.getClassLoader()
    .getResourceAsStream("processes/two-level-review.bpmn20.xml")){
   assertNotNull(in);
   return new String(in.readAllBytes(),StandardCharsets.UTF_8);
  }
 }
 @Test void dynamicDeploymentStartsActualTwoStageBpmn()throws Exception{
  ProcessEngine engine=ProcessEngineConfiguration
    .createStandaloneInMemProcessEngineConfiguration()
    .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
    .buildProcessEngine();
  try{
   var deployment=new CertifiedWorkflowDeployment(
      engine.getRepositoryService(),engine.getRuntimeService(),true);
   String xml=template(),org=UUID.randomUUID().toString();
   CertifiedWorkflowDeployment.verify(xml);
   String process=deployment.start(xml,UUID.randomUUID().toString(),org);
   assertEquals(org,engine.getRuntimeService().getVariable(process,"tenantId"));
   assertEquals(false,engine.getRuntimeService().getVariable(process,"sqlExecutionEnabled"));
   var first=engine.getTaskService().createTaskQuery().processInstanceId(process).singleResult();
   assertNotNull(first);
   assertEquals("ownerReview",first.getTaskDefinitionKey());
   engine.getTaskService().complete(first.getId());
   var second=engine.getTaskService().createTaskQuery().processInstanceId(process).singleResult();
   assertNotNull(second);
   assertEquals("dbaReview",second.getTaskDefinitionKey());
   engine.getTaskService().complete(second.getId());
   assertTrue(engine.getRuntimeService().createProcessInstanceQuery()
      .processInstanceId(process).list().isEmpty());
   assertEquals(1,engine.getRepositoryService().createDeploymentQuery()
     .deploymentName("dg-certified-"+java.util.HexFormat.of().formatHex(
       java.security.MessageDigest.getInstance("SHA-256")
          .digest(xml.getBytes(StandardCharsets.UTF_8)))).count());
  }finally{engine.close();}
 }
 @Test void rejectsScriptServiceTaskAndExternalEntity()throws Exception{
  String good=template();
  for(String invalid:new String[]{
      good.replace("<startEvent", "<scriptTask id=\"evil\"/><startEvent"),
      good.replace("flowable:candidateGroups=\"dba\"","flowable:candidateGroups=\"admins\""),
      good.replace("sourceRef=\"start\"","sourceRef=\"dbaReview\""),
      good.replace("<definitions","<!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><definitions")
  }){
   assertThrows(SecurityException.class,()->CertifiedWorkflowDeployment.verify(invalid));
  }
 }
 @Test void deploymentIsDisabledUntilCertified()throws Exception{
  ProcessEngine engine=ProcessEngineConfiguration
    .createStandaloneInMemProcessEngineConfiguration()
    .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
    .buildProcessEngine();
  try{
   var deployment=new CertifiedWorkflowDeployment(
     engine.getRepositoryService(),engine.getRuntimeService(),false);
   assertThrows(SecurityException.class,()->deployment.start(
     template(),UUID.randomUUID().toString(),UUID.randomUUID().toString()));
  }finally{engine.close();}
 }
}
