package io.github.mcpdataguard.workflow.contract;

import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import org.xml.sax.InputSource;
import org.w3c.dom.Element;

/**
 * Portable, canonical BPMN graph contract shared by server and Flowable.
 * Arbitrary BPMN XML and expression languages are never accepted as inputs.
 */
public final class SafeApprovalGraph {
 private SafeApprovalGraph(){}
 public record Step(String role,String label){}
 public record Design(List<Step> steps,boolean highRiskOnlyLast){}
 private static boolean validRole(String v){
  return v!=null&&v.matches("[a-z][a-z0-9_-]{2,31}");
 }
 private static boolean validLabel(String v){
  return v!=null&&v.matches("[\\p{L}\\p{N} _-]{2,80}");
 }
 public static void validate(Design graph){
  if(graph==null||graph.steps()==null||graph.steps().size()<2||graph.steps().size()>6)
   throw new SecurityException("APPROVAL_STEP_COUNT_OUT_OF_RANGE");
  var roles=new java.util.HashSet<String>();
  for(Step step:graph.steps()){
   if(step==null||!validRole(step.role())||!validLabel(step.label())||
      !roles.add(step.role()))
    throw new SecurityException("INVALID_OR_DUPLICATE_REVIEWER_ROLE");
  }
  if(graph.highRiskOnlyLast()&&graph.steps().size()<3)
   throw new SecurityException("RISK_BRANCH_NEEDS_THREE_STEPS");
 }
 public static String compile(Design graph){
  validate(graph);
  StringBuilder s=new StringBuilder();
  s.append("""
    <?xml version="1.0" encoding="UTF-8"?>
    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://mcp-dataguard.dev/workflows">
      <process id="safeWorkflowV2" name="Certified approval workflow" isExecutable="true">
        <startEvent id="start"/>
    """);
  int count=graph.steps().size();
  for(int i=1;i<=count;i++){
   Step step=graph.steps().get(i-1);
   s.append("    <userTask id=\"review").append(i).append("\" name=\"")
     .append(step.label()).append("\" flowable:candidateGroups=\"")
     .append(step.role()).append("\"/>\n");
  }
  int always=graph.highRiskOnlyLast()?count-1:count;
  s.append("    <sequenceFlow id=\"edge0\" sourceRef=\"start\" targetRef=\"review1\"/>\n");
  for(int i=1;i<always;i++){
   s.append("    <sequenceFlow id=\"edge").append(i).append("\" sourceRef=\"review")
     .append(i).append("\" targetRef=\"review").append(i+1).append("\"/>\n");
  }
  if(graph.highRiskOnlyLast()){
   s.append("""
         <exclusiveGateway id="riskGate" default="skipRisk"/>
    """);
   s.append("    <sequenceFlow id=\"toRisk\" sourceRef=\"review")
     .append(always).append("\" targetRef=\"riskGate\"/>\n");
   s.append("""
         <sequenceFlow id="takeRisk" sourceRef="riskGate" targetRef="review
    """.stripTrailing()).append(count).append("\" >")
     .append("<conditionExpression xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"tFormalExpression\"><![CDATA[${riskHigh}]]></conditionExpression></sequenceFlow>\n");
   s.append("    <sequenceFlow id=\"skipRisk\" sourceRef=\"riskGate\" targetRef=\"end\"/>\n");
   s.append("    <sequenceFlow id=\"riskDone\" sourceRef=\"review").append(count)
     .append("\" targetRef=\"end\"/>\n");
  }else{
   s.append("    <sequenceFlow id=\"toEnd\" sourceRef=\"review")
     .append(count).append("\" targetRef=\"end\"/>\n");
  }
  s.append("""
        <endEvent id="end"/>
      </process>
    </definitions>
    """);
  return s.toString();
 }
 public static Design parseCertified(String xml){
  if(xml==null||xml.length()>20000||xml.contains("<!DOCTYPE")||xml.contains("<!ENTITY"))
   throw new SecurityException("UNSAFE_WORKFLOW_XML");
  try{
   var factory=DocumentBuilderFactory.newInstance();
   factory.setNamespaceAware(true);
   factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
   factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
   factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
   factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
   var doc=factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
   var process=doc.getElementsByTagNameNS(
     "http://www.omg.org/spec/BPMN/20100524/MODEL","process");
   if(process.getLength()!=1||!"safeWorkflowV2".equals(((Element)process.item(0)).getAttribute("id")))
    throw new SecurityException("INVALID_CERTIFIED_WORKFLOW_KEY");
   var tasks=doc.getElementsByTagNameNS(
     "http://www.omg.org/spec/BPMN/20100524/MODEL","userTask");
   var steps=new ArrayList<Step>();
   for(int i=0;i<tasks.getLength();i++){
    Element e=(Element)tasks.item(i);
    if(!("review"+(i+1)).equals(e.getAttribute("id")))
     throw new SecurityException("NON_CANONICAL_STEP_ID");
    steps.add(new Step(e.getAttributeNS("http://flowable.org/bpmn","candidateGroups"),
      e.getAttribute("name")));
   }
   boolean high=doc.getElementsByTagNameNS(
     "http://www.omg.org/spec/BPMN/20100524/MODEL","exclusiveGateway").getLength()==1;
   Design graph=new Design(List.copyOf(steps),high);
   validate(graph);
   if(!compile(graph).equals(xml))
    throw new SecurityException("NON_CANONICAL_BPMN_DEFINITION");
   return graph;
  }catch(SecurityException e){throw e;}
   catch(Exception e){throw new SecurityException("INVALID_APPROVAL_XML");}
 }
 public static int requiredReviewers(String xml,boolean highRisk){
  if(xml!=null&&xml.contains("id=\"safeWorkflowV2\"")){
   Design graph=parseCertified(xml);
   return graph.steps().size()-(graph.highRiskOnlyLast()&&!highRisk?1:0);
  }
  return 2; // Certified legacy two-level process.
 }
 public static String roleAt(String xml,int stage,boolean highRisk){
  if(xml!=null&&xml.contains("id=\"safeWorkflowV2\"")){
   var graph=parseCertified(xml);
   if(stage<1||stage>requiredReviewers(xml,highRisk))
    throw new SecurityException("INVALID_REVIEW_STAGE");
   return graph.steps().get(stage-1).role();
  }
  if(stage==1)return "db-owner";
  if(stage==2)return "dba";
  throw new SecurityException("INVALID_LEGACY_REVIEW_STAGE");
 }
}
