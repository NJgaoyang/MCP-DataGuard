package io.github.mcpdataguard.workflow;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * Certified dynamic Flowable deployment: finite allowlisted BPMN graph only.
 * No external entities, listeners, scripts, expressions or service tasks.
 * Deployment is distinct from any SQL execution permission.
 */
@Service
public class CertifiedWorkflowDeployment {
 private static final String NS="http://www.omg.org/spec/BPMN/20100524/MODEL";
 private static final String EXT="http://flowable.org/bpmn";
 private final RepositoryService repository;
 private final RuntimeService runtime;
 private final boolean enabled;
 public CertifiedWorkflowDeployment(RepositoryService repository,RuntimeService runtime,
   @Value("${dataguard.workflow.dynamic-deployment.enabled:false}") boolean enabled){
  this.repository=repository;this.runtime=runtime;this.enabled=enabled;
 }
 public boolean enabled(){return enabled;}
 public String start(String xml,String businessKey,String tenantId){
  if(!enabled)throw new SecurityException("DYNAMIC_WORKFLOW_DISABLED");
  verify(xml);
  String digest=digest(xml);
  String deploymentName="dg-certified-"+digest;
  var previous=repository.createDeploymentQuery().deploymentName(deploymentName)
    .orderByDeploymentTime().desc().listPage(0,1);
  String definitionId;
  if(previous.isEmpty()){
   var deployed=repository.createDeployment().name(deploymentName)
     .addString("certified-review-"+digest+".bpmn20.xml",xml).deploy();
   var definition=repository.createProcessDefinitionQuery()
     .deploymentId(deployed.getId()).singleResult();
   if(definition==null || !"sqlTwoLevelReview".equals(definition.getKey()))
    throw new SecurityException("INVALID_CERTIFIED_DEFINITION");
   definitionId=definition.getId();
  }else{
   var definition=repository.createProcessDefinitionQuery()
     .deploymentId(previous.get(0).getId()).singleResult();
   if(definition==null || !"sqlTwoLevelReview".equals(definition.getKey()))
    throw new SecurityException("CERTIFIED_DEFINITION_MISSING");
   definitionId=definition.getId();
  }
  return runtime.startProcessInstanceById(definitionId,businessKey,
      Map.of("tenantId",tenantId,"sqlExecutionEnabled",false)).getId();
 }
 public static void verify(String xml){
  if(xml==null || xml.length()>12000 || xml.contains("<!DOCTYPE") ||
     xml.contains("<!ENTITY"))throw new SecurityException("UNSAFE_BPMN");
  try{
   DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
   f.setNamespaceAware(true);
   f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
   f.setFeature("http://xml.org/sax/features/external-general-entities",false);
   f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
   f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
   f.setXIncludeAware(false);f.setExpandEntityReferences(false);
   f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
   f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
   var doc=f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
   var all=doc.getElementsByTagName("*");
   Map<String,Integer> counts=new java.util.HashMap<>();
   Set<String> allowed=Set.of("definitions","process","startEvent",
      "sequenceFlow","userTask","endEvent");
   for(int i=0;i<all.getLength();i++){
    Element e=(Element)all.item(i);
    if(!NS.equals(e.getNamespaceURI())||!allowed.contains(e.getLocalName()))
     throw new SecurityException("UNSAFE_BPMN_ELEMENT");
    counts.merge(e.getLocalName(),1,Integer::sum);
    var attrs=e.getAttributes();
    for(int j=0;j<attrs.getLength();j++){
     var attr=attrs.item(j);
     if(attr.getNodeName().startsWith("xmlns"))continue;
     String name=attr.getLocalName(),ns=attr.getNamespaceURI();
     if(!((ns==null||ns.isEmpty())&&Set.of(
        "id","name","isExecutable","sourceRef","targetRef","targetNamespace").contains(name))
       && !(EXT.equals(ns)&&"candidateGroups".equals(name)))
      throw new SecurityException("UNSAFE_BPMN_ATTRIBUTE");
    }
    if("userTask".equals(e.getLocalName())){
     String id=e.getAttribute("id"),group=e.getAttributeNS(EXT,"candidateGroups");
     if(!("ownerReview".equals(id)&&"db-owner".equals(group)) &&
        !("dbaReview".equals(id)&&"dba".equals(group)))
      throw new SecurityException("UNSAFE_APPROVER");
     if(!e.getAttribute("name").matches("[\\p{L}\\p{N} _-]{2,80}"))
      throw new SecurityException("UNSAFE_TASK_LABEL");
    }
   }
   if(counts.getOrDefault("definitions",0)!=1 ||
      counts.getOrDefault("process",0)!=1 ||
      counts.getOrDefault("startEvent",0)!=1 ||
      counts.getOrDefault("endEvent",0)!=1 ||
      counts.getOrDefault("sequenceFlow",0)!=3 ||
      counts.getOrDefault("userTask",0)!=2)
    throw new SecurityException("UNSAFE_BPMN_TOPOLOGY");
   Element process=(Element)doc.getElementsByTagNameNS(NS,"process").item(0);
   if(!"sqlTwoLevelReview".equals(process.getAttribute("id"))||
      !"true".equals(process.getAttribute("isExecutable")))
    throw new SecurityException("UNSAFE_PROCESS_ID");
   Map<String,String[]> edges=Map.of("f1",new String[]{"start","ownerReview"},
    "f2",new String[]{"ownerReview","dbaReview"},
    "f3",new String[]{"dbaReview","end"});
   var flows=doc.getElementsByTagNameNS(NS,"sequenceFlow");
   for(int i=0;i<flows.getLength();i++){
    Element flow=(Element)flows.item(i);
    String[] target=edges.get(flow.getAttribute("id"));
    if(target==null||!target[0].equals(flow.getAttribute("sourceRef"))||
       !target[1].equals(flow.getAttribute("targetRef")))
     throw new SecurityException("UNSAFE_BPMN_EDGE");
   }
  }catch(SecurityException e){throw e;}
   catch(Exception e){throw new SecurityException("INVALID_BPMN_XML");}
 }
 private static String digest(String xml){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
    .digest(xml.getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
 }
}
