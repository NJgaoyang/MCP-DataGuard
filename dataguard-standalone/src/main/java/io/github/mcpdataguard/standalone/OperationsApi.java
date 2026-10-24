package io.github.mcpdataguard.standalone;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationsApi {
 private final JdbcTemplate jdbc;
 public OperationsApi(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @GetMapping("/summary")
 public Map<String,Object> summary(Authentication auth){
  Guard.requireAdmin(auth);
  long denied=jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SQL_READ' AND outcome='DENIED'",Long.class);
  long pending=jdbc.queryForObject("SELECT COUNT(*) FROM approval_requests WHERE status='PENDING' AND expires_at>NOW(3)",Long.class);
  long alerts=jdbc.queryForObject("SELECT COUNT(*) FROM security_alerts WHERE status='OPEN'",Long.class);
  return Map.of("deniedRequests",denied,"pendingApprovals",pending,"openAlerts",alerts);
 }
 @GetMapping("/live")
 public Map<String,Object> live(Authentication auth){
  Guard.requireAdmin(auth);
  Map<String,Object> counts=jdbc.queryForMap("SELECT COUNT(*) requests,COALESCE(SUM(outcome IN ('DENIED','ERROR','FAILED')),0) failures FROM audit_logs WHERE action='SQL_READ' AND event_time>=NOW(3)-INTERVAL 5 MINUTE");
  long n=((Number)counts.get("requests")).longValue(),failures=((Number)counts.get("failures")).longValue();
  long durationSamples=jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SQL_READ' AND outcome='SUCCESS' AND duration_ms IS NOT NULL AND event_time>=NOW(3)-INTERVAL 5 MINUTE",Long.class);
  long p95=0;
  if(durationSamples>0)p95=jdbc.queryForObject("SELECT duration_ms FROM audit_logs WHERE action='SQL_READ' AND outcome='SUCCESS' AND duration_ms IS NOT NULL AND event_time>=NOW(3)-INTERVAL 5 MINUTE ORDER BY duration_ms LIMIT 1 OFFSET ?",Long.class,Math.max(0,(long)Math.ceil(durationSamples*.95)-1));
  long pending=jdbc.queryForObject("SELECT COUNT(*) FROM approval_requests WHERE status='PENDING' AND expires_at>NOW(3)",Long.class);
  long alerts=jdbc.queryForObject("SELECT COUNT(*) FROM security_alerts WHERE status='OPEN'",Long.class);
  Map<String,Object> result=new LinkedHashMap<>(Map.of("windowSeconds",300,"sampledAt",Instant.now().toString(),"requests",n,"qps",Math.round(n/300.0*1000)/1000.0,"failureRate",n==0?0.0:Math.round(failures*10000.0/n)/10000.0,"pendingApprovals",pending,"openAlerts",alerts));
  result.put("p95ReadLatencyMs",durationSamples>0?p95:null);result.put("latencySamples",durationSamples);return result;
 }
 @GetMapping("/trends")
 public List<Map<String,Object>> trends(Authentication auth){
  Guard.requireAdmin(auth);
  LocalDate first=LocalDate.now().minusDays(13),today=LocalDate.now();
  Map<LocalDate,Map<String,Long>> days=new LinkedHashMap<>();
  for(LocalDate date=first;!date.isAfter(today);date=date.plusDays(1))days.put(date,new LinkedHashMap<>(Map.of("requests",0L,"denied",0L,"completed",0L,"approvals",0L,"approved",0L,"alerts",0L)));
  jdbc.query("SELECT DATE(event_time) day,COUNT(*) requests,COALESCE(SUM(outcome IN ('DENIED','ERROR','FAILED')),0) denied,COALESCE(SUM(outcome='SUCCESS'),0) completed FROM audit_logs WHERE action='SQL_READ' AND event_time>=? GROUP BY DATE(event_time)",(rs,i)->{
   var row=days.get(rs.getDate("day").toLocalDate());if(row!=null){row.put("requests",rs.getLong("requests"));row.put("denied",rs.getLong("denied"));row.put("completed",rs.getLong("completed"));}return null;
  },java.sql.Date.valueOf(first));
  jdbc.query("SELECT DATE(created_at) day,COUNT(*) approvals,COALESCE(SUM(status='APPROVED'),0) approved FROM approval_requests WHERE created_at>=? GROUP BY DATE(created_at)",(rs,i)->{
   var row=days.get(rs.getDate("day").toLocalDate());if(row!=null){row.put("approvals",rs.getLong("approvals"));row.put("approved",rs.getLong("approved"));}return null;
  },java.sql.Date.valueOf(first));
  jdbc.query("SELECT DATE(event_time) day,COUNT(*) alerts FROM security_alerts WHERE event_time>=? GROUP BY DATE(event_time)",(rs,i)->{
   var row=days.get(rs.getDate("day").toLocalDate());if(row!=null)row.put("alerts",rs.getLong("alerts"));return null;
  },java.sql.Date.valueOf(first));
  List<Map<String,Object>> result=new ArrayList<>();days.forEach((date,data)->{Map<String,Object> row=new LinkedHashMap<>();row.put("day",date.toString());row.putAll(data);result.add(row);});
  return result;
 }
}
