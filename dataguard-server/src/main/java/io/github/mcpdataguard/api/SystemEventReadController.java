package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only scoped administrative change history. No SQL payloads or credentials. */
@RestController
@RequestMapping("/api/v1/system-events")
public class SystemEventReadController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public SystemEventReadController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 @GetMapping
 public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt,
     @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset){
  UserPrincipal actor=users.requireUser(jwt);
  users.requirePermission(actor,"identity:admin");
  if(limit<1||limit>100||offset<0||offset>100000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
  return jdbc.query("""
   SELECT id,actor,operation,resource_type,resource_id,created_at
     FROM dg_system_event WHERE org_id=?
   ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
   """,(rs,i)->{
    Map<String,Object> row=new java.util.LinkedHashMap<>();
    row.put("id",rs.getString("id"));
    row.put("actor",rs.getString("actor"));
    row.put("operation",rs.getString("operation"));
    row.put("resourceType",rs.getString("resource_type"));
    row.put("resourceId",rs.getString("resource_id"));
    row.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());
    return row;
   },actor.orgId(),limit,offset);
 }
}
