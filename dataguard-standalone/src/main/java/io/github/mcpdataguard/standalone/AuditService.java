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
  String safe=detail==null?"":detail.replaceAll("[\\r\\n\\t]"," ");
  if(safe.length()>900)safe=safe.substring(0,900);
  jdbc.update("INSERT INTO audit_logs(actor_user_id,actor_username,action,resource_type,resource_id,outcome,detail) "+
     "VALUES((SELECT id FROM app_users WHERE username=?),?,?,?,?,?,?)",
    username,username,action,type,resource,outcome,safe);
 }
 @RestController
 @RequestMapping("/api/audit")
 public static class Controller {
  private final JdbcTemplate jdbc;
  public Controller(JdbcTemplate jdbc){this.jdbc=jdbc;}
  @GetMapping
  public List<Map<String,Object>> list(Authentication auth){
   Guard.requireAdmin(auth);
   return jdbc.queryForList("SELECT id,event_time,actor_username,action,resource_type,resource_id,outcome,detail FROM audit_logs ORDER BY id DESC LIMIT 200");
  }
 }
}