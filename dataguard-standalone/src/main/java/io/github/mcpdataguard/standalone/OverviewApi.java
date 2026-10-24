package io.github.mcpdataguard.standalone;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/overview")
public class OverviewApi {
 private final JdbcTemplate jdbc;
 public OverviewApi(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @GetMapping
 public Map<String,Object> overview(Authentication auth,@RequestParam(defaultValue="14") int days){
  if(!List.of(7,14,30).contains(days))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"统计周期只支持 7、14 或 30 天");
  boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
  LocalDate today=jdbc.queryForObject("SELECT CURRENT_DATE",Date.class).toLocalDate(),first=today.minusDays(days-1);
  Date from=Date.valueOf(first);
  String scope=admin?"":" AND actor_username=?";
  Object[] args=admin?new Object[]{from}:new Object[]{from,auth.getName()};
  Map<String,Object> counts=jdbc.queryForMap("SELECT COUNT(*) requests,COALESCE(SUM(outcome='SUCCESS'),0) completed,COALESCE(SUM(outcome='DENIED'),0) denied,COALESCE(SUM(outcome IN ('ERROR','FAILED')),0) failed FROM audit_logs WHERE action='SQL_READ' AND event_time>=?"+scope,args);
  String approvalScope=admin?"":" AND (requester.username=? OR reviewer.username=?)";
  Object[] approvalArgs=admin?new Object[]{from}:new Object[]{from,auth.getName(),auth.getName()};
  Map<String,Object> approvalStats=jdbc.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(a.status='APPROVED'),0) approved,COALESCE(SUM(a.status='REJECTED'),0) rejected FROM approval_requests a JOIN app_users requester ON requester.id=a.requester_user_id JOIN app_users reviewer ON reviewer.id=a.approver_user_id WHERE a.created_at>=?"+approvalScope,approvalArgs);
  long pending=jdbc.queryForObject("SELECT COUNT(*) FROM approval_requests a JOIN app_users u ON u.id=a.approver_user_id WHERE u.username=? AND a.status='PENDING' AND a.expires_at>NOW(3)",Long.class,auth.getName());
  Map<String,Object> agentStats=jdbc.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(t.revoked_at IS NULL AND t.expires_at>NOW(3)),0) active FROM app_agent_tokens t JOIN app_users u ON u.id=t.owner_user_id WHERE u.username=?",auth.getName());
  Map<String,Object> sourceStats=admin?jdbc.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(enabled=1),0) enabled FROM data_sources"):
   jdbc.queryForMap("SELECT COUNT(DISTINCT s.id) total,COUNT(DISTINCT s.id) enabled FROM data_sources s JOIN read_table_allowlist w ON w.source_id=s.id AND w.enabled=1 JOIN approval_requests a ON a.source_id=s.id AND a.table_name=w.table_name JOIN app_users u ON u.id=a.requester_user_id WHERE u.username=? AND s.enabled=1 AND a.status='APPROVED' AND a.expires_at>NOW(3)",auth.getName());
  long alerts=admin?jdbc.queryForObject("SELECT COUNT(*) FROM security_alerts WHERE status='OPEN'",Long.class):0;
  Map<LocalDate,Map<String,Object>> trend=new LinkedHashMap<>();
  for(LocalDate d=first;!d.isAfter(today);d=d.plusDays(1))trend.put(d,new LinkedHashMap<>(Map.of("day",d.toString(),"requests",0L,"completed",0L,"denied",0L,"failed",0L,"approvals",0L,"approved",0L,"rejected",0L)));
  jdbc.query("SELECT DATE(event_time) day,COUNT(*) requests,COALESCE(SUM(outcome='SUCCESS'),0) completed,COALESCE(SUM(outcome='DENIED'),0) denied,COALESCE(SUM(outcome IN ('ERROR','FAILED')),0) failed FROM audit_logs WHERE action='SQL_READ' AND event_time>=?"+scope+" GROUP BY DATE(event_time)",(rs,n)->{var row=trend.get(rs.getDate("day").toLocalDate());if(row!=null)for(String k:List.of("requests","completed","denied","failed"))row.put(k,rs.getLong(k));return null;},args);
  jdbc.query("SELECT DATE(a.created_at) day,COUNT(*) approvals,COALESCE(SUM(a.status='APPROVED'),0) approved,COALESCE(SUM(a.status='REJECTED'),0) rejected FROM approval_requests a JOIN app_users requester ON requester.id=a.requester_user_id JOIN app_users reviewer ON reviewer.id=a.approver_user_id WHERE a.created_at>=?"+approvalScope+" GROUP BY DATE(a.created_at)",(rs,n)->{var row=trend.get(rs.getDate("day").toLocalDate());if(row!=null)for(String k:List.of("approvals","approved","rejected"))row.put(k,rs.getLong(k));return null;},approvalArgs);
  List<Map<String,Object>> recent=jdbc.queryForList("SELECT a.id,s.display_name sourceName,a.table_name tableName,requester.display_name requester,CASE WHEN a.status='PENDING' AND a.expires_at<=NOW(3) THEN 'EXPIRED' ELSE a.status END status,a.expires_at expiresAt,a.created_at createdAt FROM approval_requests a JOIN data_sources s ON s.id=a.source_id JOIN app_users requester ON requester.id=a.requester_user_id JOIN app_users reviewer ON reviewer.id=a.approver_user_id WHERE 1=1"+approvalScope+" ORDER BY a.created_at DESC LIMIT 5",admin?new Object[]{}:new Object[]{auth.getName(),auth.getName()});
  Map<String,Object> out=new LinkedHashMap<>();out.put("days",days);out.put("scope",admin?"PLATFORM":"SELF");out.put("from",first.toString());out.put("to",today.toString());out.put("requests",counts);out.put("approvals",approvalStats);out.put("agents",agentStats);out.put("sources",sourceStats);out.put("pendingApprovals",pending);out.put("openAlerts",alerts);out.put("trends",new ArrayList<>(trend.values()));out.put("recentApprovals",recent);return out;
 }
}
