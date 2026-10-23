package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.workflow.contract.SafeApprovalGraph;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Fail-closed approval-to-execution eligibility inspection.
 * This service can NEVER schedule or physically execute SQL.
 * It is a prerequisite for a future separately certified execution worker.
 */
@Service
public class ExecutionEligibilityService {
 private final JdbcTemplate jdbc;
 public ExecutionEligibilityService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Eligibility(boolean eligible,String reason,UUID requestId){}
 private record Approval(UUID requestId,String sqlDigest,String payloadHmac,
    String approvalState,String executionState,long reviewers,
    long distinctReviewers,long publishedStages,boolean flowableComplete,
    String workflowXml,String workflowDigest,String risk){}
 public Eligibility inspect(UUID org,UUID approvalId,String expectedSqlFingerprint,
                           String expectedPayloadHmac){
  if(org==null||approvalId==null||expectedSqlFingerprint==null||
     !expectedSqlFingerprint.matches("[a-f0-9]{64}")||
     expectedPayloadHmac==null||!expectedPayloadHmac.matches("[a-f0-9]{64}"))
   return new Eligibility(false,"INVALID_EXECUTION_INTENT",null);
  List<Approval> approvals=jdbc.query("""
    SELECT r.id,r.sql_fingerprint,p.canonical_payload_hmac,
      a.approval_status,a.execution_status,v.bpmn_xml,v.bpmn_digest,r.risk_level,
      (SELECT count(*) FROM dg_sql_approval_action ac
       WHERE ac.org_id=a.org_id AND ac.approval_id=a.id AND ac.action='APPROVE') AS reviews,
      (SELECT count(DISTINCT ac.reviewer_user_id) FROM dg_sql_approval_action ac
       WHERE ac.org_id=a.org_id AND ac.approval_id=a.id AND ac.action='APPROVE') AS distinct_reviews,
      (a.process_instance_id IS NOT NULL
       AND NOT EXISTS(
        SELECT 1 FROM dg_sql_approval_action ac
        WHERE ac.org_id=a.org_id AND ac.approval_id=a.id AND ac.action='APPROVE'
          AND (ac.flowable_task_id IS NULL OR
               ac.flowable_task_id LIKE 'platform:%')
       )
       AND NOT EXISTS(
        SELECT 1 FROM dg_outbox_event e WHERE e.org_id=a.org_id
          AND e.aggregate_id=a.id
          AND e.event_type IN ('APPROVAL_REQUESTED','APPROVAL_STEP_APPROVED')
          AND e.state<>'PUBLISHED'
       )
      ) AS has_flowable,
      (SELECT count(*) FROM dg_outbox_event e
       WHERE e.org_id=a.org_id AND e.aggregate_id=a.id
         AND e.event_type='APPROVAL_STEP_APPROVED'
         AND e.state='PUBLISHED') AS published_stages
    FROM dg_sql_approval a
    JOIN dg_sql_request r ON r.org_id=a.org_id AND r.id=a.request_id
    JOIN dg_sql_request_payload p ON p.org_id=r.org_id AND p.request_id=r.id
    JOIN dg_workflow_version v ON v.org_id=a.org_id AND v.id=a.workflow_version_id
    WHERE a.org_id=? AND a.id=? AND a.approval_status='APPROVED'
      AND a.execution_status='NOT_SCHEDULED'
      AND a.expires_at>now() AND v.status IN ('PUBLISHED','RETIRED')
      AND r.decision='REQUIRE_APPROVAL'
      AND r.request_status='PENDING_APPROVAL'
      AND NOT EXISTS(SELECT 1 FROM dg_execution_task t
         WHERE t.org_id=a.org_id AND t.approval_id=a.id)
    """,(rs,i)->new Approval(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
      rs.getString(4),rs.getString(5),rs.getLong(9),rs.getLong(10),
      rs.getLong(12),rs.getBoolean(11),rs.getString(6),rs.getString(7),
      rs.getString(8)),org,approvalId);
  if(approvals.size()!=1)return new Eligibility(false,"APPROVAL_NOT_ELIGIBLE",null);
  Approval row=approvals.get(0);
  int needed;
  try{
   String actual=java.util.HexFormat.of().formatHex(
     MessageDigest.getInstance("SHA-256")
     .digest(row.workflowXml().getBytes(StandardCharsets.UTF_8)));
   if(!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
      row.workflowDigest().getBytes(StandardCharsets.US_ASCII)))
    return new Eligibility(false,"WORKFLOW_VERSION_DIGEST_MISMATCH",row.requestId());
   needed=SafeApprovalGraph.requiredReviewers(row.workflowXml(),
      ("HIGH".equals(row.risk()) || "CRITICAL".equals(row.risk())));
  }catch(Exception e){
   return new Eligibility(false,"WORKFLOW_VERSION_NOT_CERTIFIED",row.requestId());
  }
  if(row.reviewers()!=needed||row.distinctReviewers()!=needed||
      row.publishedStages()!=needed||!row.flowableComplete())
   return new Eligibility(false,"INCOMPLETE_INDEPENDENT_APPROVAL",row.requestId());
  if(!MessageDigest.isEqual(expectedSqlFingerprint.getBytes(StandardCharsets.US_ASCII),
      row.sqlDigest().getBytes(StandardCharsets.US_ASCII)) ||
     !MessageDigest.isEqual(expectedPayloadHmac.getBytes(StandardCharsets.US_ASCII),
      row.payloadHmac().getBytes(StandardCharsets.US_ASCII)))
   return new Eligibility(false,"EXECUTION_PAYLOAD_MISMATCH",row.requestId());
  return new Eligibility(true,"ELIGIBILITY_ONLY_EXECUTOR_DISABLED",row.requestId());
 }
}
