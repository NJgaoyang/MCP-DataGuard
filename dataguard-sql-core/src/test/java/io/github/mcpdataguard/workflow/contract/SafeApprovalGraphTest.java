package io.github.mcpdataguard.workflow.contract;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeApprovalGraphTest {
 private static SafeApprovalGraph.Design graph(boolean high){
  return new SafeApprovalGraph.Design(List.of(
   new SafeApprovalGraph.Step("data-owner","Data Owner"),
   new SafeApprovalGraph.Step("security-auditor","Security Audit"),
   new SafeApprovalGraph.Step("db-admin","DBA Approval")),high);
 }
 @Test void certifiedGraphHasStableCanonicalXmlAndQuorum(){
  for(boolean high:List.of(false,true)){
   var xml=SafeApprovalGraph.compile(graph(high));
   assertEquals(graph(high),SafeApprovalGraph.parseCertified(xml));
   assertEquals(high?3:2,SafeApprovalGraph.requiredReviewers(xml,high));
   assertEquals("data-owner",SafeApprovalGraph.roleAt(xml,1,high));
   assertEquals("security-auditor",SafeApprovalGraph.roleAt(xml,2,high));
   if(high)assertEquals("db-admin",SafeApprovalGraph.roleAt(xml,3,true));
  }
 }
 @Test void arbitraryXmlAndCodeInjectionAreRejected(){
  var good=SafeApprovalGraph.compile(graph(true));
  assertThrows(SecurityException.class,()->
   SafeApprovalGraph.parseCertified(good.replace("data-owner","invalid!role")));
  assertThrows(SecurityException.class,()->SafeApprovalGraph.validate(
   new SafeApprovalGraph.Design(List.of(
    new SafeApprovalGraph.Step("team-one","Safe Reviewer"),
    new SafeApprovalGraph.Step("team-one","Duplicate")),false)));
  assertThrows(SecurityException.class,()->SafeApprovalGraph.validate(
   new SafeApprovalGraph.Design(List.of(
    new SafeApprovalGraph.Step("team-one","Unsafe! Expression"),
    new SafeApprovalGraph.Step("team-two","Second Reviewer")),false)));
 }
}
