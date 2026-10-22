package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Write-authorized resource details, for editing only. No secrets are exposed. */
@RestController
@RequestMapping("/api/v1")
public final class ManagementEditController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public ManagementEditController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 @GetMapping("/agents/{id}/edit")
 public AgentEdit agent(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){
  UserPrincipal u=users.requireUser(jwt);
  users.requirePermission(u,"agent:write");
  List<AgentEdit> result=jdbc.query("""
   SELECT id,code,name,description,status,rate_limit_per_minute,
          concurrency_limit,max_rows,max_execution_ms,version
     FROM dg_agent WHERE org_id=? AND id=?
   """,(rs,row)->new AgentEdit(
    rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),
    rs.getString("description"),rs.getString("status"),rs.getInt("rate_limit_per_minute"),
    rs.getInt("concurrency_limit"),rs.getInt("max_rows"),
    rs.getInt("max_execution_ms"),rs.getLong("version")),u.orgId(),id);
  if(result.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found");
  return result.get(0);
 }
 @GetMapping("/datasources/{id}/edit")
 public DatasourceEdit datasource(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){
  UserPrincipal u=users.requireUser(jwt);
  users.requirePermission(u,"datasource:write");
  List<DatasourceEdit> result=jdbc.query("""
   SELECT id,code,name,db_type,environment,host,port,database_name,status,version
     FROM dg_datasource WHERE org_id=? AND id=?
   """,(rs,row)->new DatasourceEdit(
    rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),
    rs.getString("db_type"),rs.getString("environment"),rs.getString("host"),
    rs.getInt("port"),rs.getString("database_name"),
    rs.getString("status"),rs.getLong("version")),u.orgId(),id);
  if(result.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Datasource not found");
  return result.get(0);
 }
 public record AgentEdit(UUID id,String code,String name,String description,String status,
                         int rateLimitPerMinute,int concurrencyLimit,int maxRows,
                         int maxExecutionMs,long version){}
 public record DatasourceEdit(UUID id,String code,String name,String dbType,String environment,
                              String host,int port,String databaseName,String status,long version){}
}
