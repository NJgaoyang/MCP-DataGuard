package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Tenant-scoped daily SQL decision and approval monitoring; no raw query text. */
@RestController
@RequestMapping("/api/v1/operations/trends")
public class OperationsTrendsController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public OperationsTrendsController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 @GetMapping
 public List<Map<String,Object>> trends(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal actor=users.requireUser(jwt);
  users.requirePermission(actor,"identity:admin");
  return jdbc.query("""
    WITH days AS (
      SELECT generate_series(CURRENT_DATE - interval '13 days',
        CURRENT_DATE,interval '1 day')::date AS d
    ), requests AS (
      SELECT received_at::date AS d,
        count(*) AS total,
        count(*) FILTER(WHERE decision='DENY') AS denied,
        count(*) FILTER(WHERE request_status='READ_DONE') AS completed
      FROM dg_sql_request
      WHERE org_id=? AND received_at>=CURRENT_DATE-interval '13 days'
      GROUP BY received_at::date
    ), approvals AS (
      SELECT created_at::date AS d,
       count(*) AS created,
       count(*) FILTER(WHERE approval_status='APPROVED') AS approved
      FROM dg_sql_approval
      WHERE org_id=? AND created_at>=CURRENT_DATE-interval '13 days'
      GROUP BY created_at::date
    ), alerts AS (
      SELECT observed_at::date AS d,count(*) AS flagged
      FROM dg_alert_event
      WHERE org_id=? AND observed_at>=CURRENT_DATE-interval '13 days'
      GROUP BY observed_at::date
    ), execution AS (
      SELECT COALESCE(started_at,enqueued_at)::date AS d,
        count(*) FILTER(WHERE task_state='SUCCEEDED') AS succeeded,
        count(*) FILTER(WHERE task_state='UNKNOWN') AS uncertain
      FROM dg_execution_task
      WHERE org_id=? AND enqueued_at>=CURRENT_DATE-interval '13 days'
      GROUP BY COALESCE(started_at,enqueued_at)::date
    ), read_latency AS (
      SELECT r.received_at::date AS d,
        count(*) FILTER(WHERE s.duration_ms>5000) AS slow
      FROM dg_sql_read_summary s JOIN dg_sql_request r
        ON r.org_id=s.org_id AND r.id=s.request_id
      WHERE s.org_id=? AND r.received_at>=CURRENT_DATE-interval '13 days'
      GROUP BY r.received_at::date
    )
    SELECT days.d,COALESCE(requests.total,0) AS requests,
      COALESCE(requests.denied,0) AS denied,
      COALESCE(requests.completed,0) AS completed,
      COALESCE(approvals.created,0) AS approvals,
      COALESCE(approvals.approved,0) AS approved,
      COALESCE(alerts.flagged,0) AS alerts,
      COALESCE(execution.succeeded,0) AS successful_writes,
      COALESCE(execution.uncertain,0) AS unknown_writes,
      COALESCE(read_latency.slow,0) AS slow_reads
    FROM days
    LEFT JOIN requests ON requests.d=days.d
    LEFT JOIN approvals ON approvals.d=days.d
    LEFT JOIN alerts ON alerts.d=days.d
    LEFT JOIN execution ON execution.d=days.d
    LEFT JOIN read_latency ON read_latency.d=days.d
    ORDER BY days.d
    """,(rs,i)->Map.<String,Object>of(
      "day",rs.getDate("d").toString(),
      "requests",rs.getLong("requests"),"denied",rs.getLong("denied"),
      "completed",rs.getLong("completed"),"approvals",rs.getLong("approvals"),
      "approved",rs.getLong("approved"),"alerts",rs.getLong("alerts"),
      "successfulWrites",rs.getLong("successful_writes"),
      "unknownWrites",rs.getLong("unknown_writes"),
      "slowReads",rs.getLong("slow_reads")),
    actor.orgId(),actor.orgId(),actor.orgId(),actor.orgId(),actor.orgId());
 }
}
