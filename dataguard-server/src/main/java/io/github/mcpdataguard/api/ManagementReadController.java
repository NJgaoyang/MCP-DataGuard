package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Initial tenant-scoped, permission-checked management endpoints.
 * Secrets, connection strings and SQL payloads are deliberately never returned. */
@RestController
@RequestMapping("/api/v1")
public class ManagementReadController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public ManagementReadController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 @GetMapping("/me")
 public Map<String,Object> me(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);
  return Map.of("userId",u.userId().toString(),"orgId",u.orgId().toString(),
   "username",u.username(),"displayName",u.displayName());
 }
 @GetMapping("/datasources")
 public List<Map<String,Object>> datasources(@AuthenticationPrincipal Jwt jwt,
     @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset){
  UserPrincipal u=users.requireUser(jwt);
  users.requirePermission(u,"datasource:read");
  verifyPage(limit,offset);
  return jdbc.query("""
   SELECT id, code, name, db_type, environment, status
   FROM dg_datasource WHERE org_id=? ORDER BY name,id LIMIT ? OFFSET ?
   """,(rs,index)->Map.<String,Object>of(
    "id",rs.getString("id"),"code",rs.getString("code"),"name",rs.getString("name"),
    "databaseType",rs.getString("db_type"),"environment",rs.getString("environment"),
    "status",rs.getString("status")),u.orgId(),limit,offset);
 }
 @GetMapping("/agents")
 public List<Map<String,Object>> agents(@AuthenticationPrincipal Jwt jwt,
     @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset){
  UserPrincipal u=users.requireUser(jwt);
  users.requirePermission(u,"agent:read");
  verifyPage(limit,offset);
  return jdbc.query("""
   SELECT id, code, name, status, rate_limit_per_minute, max_rows
   FROM dg_agent WHERE org_id=? ORDER BY name,id LIMIT ? OFFSET ?
   """,(rs,index)->Map.<String,Object>of(
    "id",rs.getString("id"),"code",rs.getString("code"),"name",rs.getString("name"),
    "status",rs.getString("status"),"rateLimitPerMinute",rs.getInt("rate_limit_per_minute"),
    "maxRows",rs.getInt("max_rows")),u.orgId(),limit,offset);
 }
 private static void verifyPage(int limit,int offset){
  if(limit<1||limit>100||offset<0||offset>100000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
 }
}
