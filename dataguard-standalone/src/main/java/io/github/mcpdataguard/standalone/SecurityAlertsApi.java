package io.github.mcpdataguard.standalone;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/security-alerts")
public class SecurityAlertsApi {
 private final JdbcTemplate jdbc;private final AuditService audit;
 public SecurityAlertsApi(JdbcTemplate jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}
 @GetMapping
 public List<Map<String,Object>> list(Authentication auth,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String severity){
  Guard.requireAdmin(auth);
  if(!List.of("","OPEN","ACKNOWLEDGED","RESOLVED").contains(status)||!List.of("","LOW","MEDIUM","HIGH","CRITICAL").contains(severity))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"筛选条件无效");
  return jdbc.queryForList("SELECT id,event_time AS eventTime,actor_username AS actor,event_type AS eventType,severity,status,resource_id AS resource,detail,acknowledged_by AS acknowledgedBy,resolved_by AS resolvedBy FROM security_alerts WHERE (?='' OR status=?) AND (?='' OR severity=?) ORDER BY event_time DESC LIMIT 100",status,status,severity,severity);
 }
 @PostMapping("/{id}/acknowledge") @Transactional
 public Map<String,String> acknowledge(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  if(jdbc.update("UPDATE security_alerts SET status='ACKNOWLEDGED',acknowledged_by=?,acknowledged_at=NOW(3) WHERE id=? AND status='OPEN'",auth.getName(),id.toString())!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"告警状态已变化");
  audit.log(auth.getName(),"ALERT_ACKNOWLEDGED","ALERT",id.toString(),"SUCCESS","");return Map.of("status","ACKNOWLEDGED");
 }
 @PostMapping("/{id}/resolve") @Transactional
 public Map<String,String> resolve(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  if(jdbc.update("UPDATE security_alerts SET status='RESOLVED',resolved_by=?,resolved_at=NOW(3) WHERE id=? AND status='ACKNOWLEDGED'",auth.getName(),id.toString())!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"请先确认告警");
  audit.log(auth.getName(),"ALERT_RESOLVED","ALERT",id.toString(),"SUCCESS","");return Map.of("status","RESOLVED");
 }
}
