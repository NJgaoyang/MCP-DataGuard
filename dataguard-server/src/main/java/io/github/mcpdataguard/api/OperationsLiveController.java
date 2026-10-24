package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped near-real-time five-minute security metrics, read-only API.
 * No raw SQL, bind parameters, credentials or individual data values exposed.
 */
@RestController
@RequestMapping("/api/v1/operations/live")
public final class OperationsLiveController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public OperationsLiveController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 @GetMapping
 public Map<String,Object> live(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal actor=users.requireUser(jwt);
  users.requirePermission(actor,"identity:admin");
  var values=jdbc.queryForObject("""
    SELECT count(*) AS requests,
       count(*) FILTER(WHERE r.request_status IN('DENIED','FAILED')) AS failures,
       COALESCE(percentile_cont(0.95) WITHIN GROUP(
         ORDER BY s.duration_ms),0) AS p95_ms
    FROM dg_sql_request r
    LEFT JOIN dg_sql_read_summary s
      ON s.org_id=r.org_id AND s.request_id=r.id
    WHERE r.org_id=? AND r.received_at>=now()-interval '5 minutes'
    """,(rs,row)->new long[]{
       rs.getLong("requests"),rs.getLong("failures"),
       Math.round(rs.getDouble("p95_ms"))
    },actor.orgId());
  if(values==null)throw new IllegalStateException("Missing aggregate metrics");
  Long pending=jdbc.queryForObject("""
    SELECT count(*) FROM dg_sql_approval
    WHERE org_id=? AND approval_status='PENDING'
    """,Long.class,actor.orgId());
  Long alerts=jdbc.queryForObject("""
    SELECT count(*) FROM dg_alert_event
    WHERE org_id=? AND event_state='OPEN'
    """,Long.class,actor.orgId());
  long n=values[0],errors=values[1];
  Map<String,Object> result=new LinkedHashMap<>();
  result.put("windowSeconds",300);
  result.put("sampledAt",Instant.now().toString());
  result.put("requests",n);
  result.put("qps",Math.round((n/300.0)*1000)/1000.0);
  result.put("failureRate",n==0?0.0:Math.round(errors*10000.0/n)/10000.0);
  result.put("p95ReadLatencyMs",values[2]);
  result.put("pendingApprovals",pending==null?0:pending);
  result.put("openAlerts",alerts==null?0:alerts);
  return result;
 }
}
