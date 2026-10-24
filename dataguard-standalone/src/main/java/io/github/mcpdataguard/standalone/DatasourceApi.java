package io.github.mcpdataguard.standalone;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DatasourceApi {
 private final JdbcTemplate jdbc;
 private final AuditService audit;
 public DatasourceApi(JdbcTemplate jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}
 @GetMapping("/sources")
 public List<Map<String,Object>> sources(){
  return jdbc.queryForList("SELECT id,code,display_name AS displayName,host,port,database_name AS databaseName,enabled FROM data_sources ORDER BY code");
 }
 public record NewSource(String code,String displayName,String host,int port,String databaseName,String readUsername,String passwordEnv){}
 @PostMapping("/sources")
 public Map<String,Object> addSource(Authentication auth,@RequestBody NewSource input){
  Guard.requireAdmin(auth);
  if(input==null||input.displayName()==null||input.displayName().isBlank()||input.displayName().length()>128||
      input.host()==null||!input.host().matches("[A-Za-z0-9.-]{1,253}")||
      input.port()<1||input.port()>65535||
      input.readUsername()==null||!input.readUsername().matches("[A-Za-z0-9_]{1,80}")||
      input.passwordEnv()==null||!input.passwordEnv().matches("DATAGUARD_TARGET_[A-Z0-9_]{3,64}_PASSWORD"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid datasource");
  String db=Guard.identifier(input.databaseName());
  String code=Guard.identifier(input.code());
  String id=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO data_sources(id,code,display_name,host,port,database_name,read_username,password_env,enabled) VALUES(?,?,?,?,?,?,?,?,0)",
     id,code,input.displayName().strip(),input.host(),input.port(),db,input.readUsername(),input.passwordEnv());
  audit.log(auth.getName(),"SOURCE_CREATED","SOURCE",id,"SUCCESS","source disabled by default");
  return Map.of("id",id,"enabled",false);
 }
 @PostMapping("/sources/{id}/enable")
 public Map<String,Object> enable(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  int n=jdbc.update("UPDATE data_sources SET enabled=1 WHERE id=?",id.toString());
  if(n!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  audit.log(auth.getName(),"SOURCE_ENABLED","SOURCE",id.toString(),"SUCCESS","read-only");
  return Map.of("enabled",true);
 }
 @GetMapping("/sources/{id}/allowlist")
 public List<Map<String,Object>> allowed(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  return jdbc.queryForList("SELECT id,table_name AS tableName,enabled,created_at AS createdAt FROM read_table_allowlist WHERE source_id=? ORDER BY table_name",id.toString());
 }
 public record AllowedTable(String tableName){}
 @PostMapping("/sources/{id}/allowlist")
 public Map<String,Object> allow(Authentication auth,@PathVariable UUID id,@RequestBody AllowedTable input){
  Guard.requireAdmin(auth);
  String table=Guard.identifier(input==null?null:input.tableName());
  String me=jdbc.queryForObject("SELECT id FROM app_users WHERE username=?",String.class,auth.getName());
  jdbc.update("INSERT INTO read_table_allowlist(id,source_id,table_name,enabled,created_by) VALUES(?,?,?,?,?) "+
       "ON DUPLICATE KEY UPDATE enabled=1,created_by=VALUES(created_by)",
       UUID.randomUUID().toString(),id.toString(),table,true,me);
  audit.log(auth.getName(),"TABLE_ALLOWED","TABLE",id+"/"+table,"SUCCESS","positive allowlist");
  return Map.of("table",table,"allowed",true);
 }
 @DeleteMapping("/sources/{id}/allowlist/{table}")
 public Map<String,Object> revoke(Authentication auth,@PathVariable UUID id,@PathVariable String table){
  Guard.requireAdmin(auth);
  table=Guard.identifier(table);
  jdbc.update("UPDATE read_table_allowlist SET enabled=0 WHERE source_id=? AND table_name=?",id.toString(),table);
  audit.log(auth.getName(),"TABLE_REVOKED","TABLE",id+"/"+table,"SUCCESS","immediate");
  return Map.of("table",table,"allowed",false);
 }
 public record AllowedColumn(String columnName){}
 @GetMapping("/sources/{id}/allowlist/{table}/columns")
 public List<Map<String,Object>> allowedColumns(Authentication auth,@PathVariable UUID id,@PathVariable String table){
  Guard.requireAdmin(auth);
  table=Guard.identifier(table);
  return jdbc.queryForList("SELECT column_name AS columnName,enabled FROM read_column_allowlist WHERE source_id=? AND table_name=? ORDER BY column_name",
      id.toString(),table);
 }
 @PostMapping("/sources/{id}/allowlist/{table}/columns")
 public Map<String,Object> allowColumn(Authentication auth,@PathVariable UUID id,
   @PathVariable String table,@RequestBody AllowedColumn input){
  Guard.requireAdmin(auth);
  table=Guard.identifier(table);
  String column=Guard.identifier(input==null?null:input.columnName());
  Integer present=jdbc.queryForObject("SELECT COUNT(*) FROM read_table_allowlist WHERE source_id=? AND table_name=? AND enabled=1",
      Integer.class,id.toString(),table);
  if(present==null||present!=1)
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Grant table before granting columns");
  String actor=jdbc.queryForObject("SELECT id FROM app_users WHERE username=?",String.class,auth.getName());
  jdbc.update("INSERT INTO read_column_allowlist(id,source_id,table_name,column_name,enabled,created_by) VALUES(?,?,?,?,1,?) "+
      "ON DUPLICATE KEY UPDATE enabled=1,created_by=VALUES(created_by)",
      UUID.randomUUID().toString(),id.toString(),table,column,actor);
  audit.log(auth.getName(),"COLUMN_ALLOWED","COLUMN",id+"/"+table+"/"+column,"SUCCESS","explicit disclosure");
  return Map.of("column",column,"visible",true);
 }
 @DeleteMapping("/sources/{id}/allowlist/{table}/columns/{column}")
 public Map<String,Object> revokeColumn(Authentication auth,@PathVariable UUID id,
   @PathVariable String table,@PathVariable String column){
  Guard.requireAdmin(auth);
  table=Guard.identifier(table);
  column=Guard.identifier(column);
  jdbc.update("UPDATE read_column_allowlist SET enabled=0 WHERE source_id=? AND table_name=? AND column_name=?",
      id.toString(),table,column);
  audit.log(auth.getName(),"COLUMN_REVOKED","COLUMN",id+"/"+table+"/"+column,"SUCCESS","redact immediately");
  return Map.of("column",column,"visible",false);
 }

}