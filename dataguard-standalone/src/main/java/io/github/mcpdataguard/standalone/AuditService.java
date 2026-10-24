package io.github.mcpdataguard.standalone;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Service
public class AuditService {
 private final JdbcTemplate jdbc;
 public AuditService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public void log(String username,String action,String type,String resource,String outcome,String detail){
  log(username,action,type,resource,outcome,detail,null);
 }
 public void log(String username,String action,String type,String resource,String outcome,String detail,Long durationMs){
  String safe=detail==null?"":detail.replaceAll("[\\r\\n\\t]"," ");
  if(safe.length()>900)safe=safe.substring(0,900);
  jdbc.update("INSERT INTO audit_logs(actor_user_id,actor_username,action,resource_type,resource_id,outcome,duration_ms,detail) "+
     "VALUES((SELECT id FROM app_users WHERE username=?),?,?,?,?,?,?,?)",
    username,username,action,type,resource,outcome,durationMs,safe);
  if("SQL_READ".equals(action)&&List.of("DENIED","ERROR","FAILED").contains(outcome)){
   jdbc.update("INSERT INTO security_alerts(id,actor_username,event_type,severity,resource_id,detail) VALUES(?,?,?,?,?,?)",
      java.util.UUID.randomUUID().toString(),username,"SQL_READ_"+outcome,
      "DENIED".equals(outcome)?"HIGH":"MEDIUM",resource,safe.substring(0,Math.min(safe.length(),500)));
  }
 }
 @RestController
 @RequestMapping("/api/audit")
 public static class Controller {
  private final JdbcTemplate jdbc;
  public Controller(JdbcTemplate jdbc){this.jdbc=jdbc;}
  @GetMapping
  public List<Map<String,Object>> list(Authentication auth,@RequestParam(defaultValue="") String action){
   Guard.requireAdmin(auth);
   if(!List.of("","SQL_READ").contains(action))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid audit filter");
   return jdbc.queryForList("SELECT id,event_time,actor_username,action,resource_type,resource_id,outcome,duration_ms,detail FROM audit_logs WHERE (?='' OR action=?) ORDER BY id DESC LIMIT 200",action,action);
  }
 }
}
