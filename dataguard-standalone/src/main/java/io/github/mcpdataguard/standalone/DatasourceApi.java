package io.github.mcpdataguard.standalone;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
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
 private final DatasourceSecrets secrets;
 public DatasourceApi(JdbcTemplate jdbc,AuditService audit,DatasourceSecrets secrets){this.jdbc=jdbc;this.audit=audit;this.secrets=secrets;}
 @GetMapping("/sources")
 public List<Map<String,Object>> sources(){
  return jdbc.queryForList("SELECT s.id,s.code,s.display_name AS displayName,s.host,s.port,s.database_name AS databaseName,s.db_type AS dbType,s.enabled,(SELECT COUNT(*) FROM read_table_allowlist w WHERE w.source_id=s.id AND w.enabled=1) allowedTables FROM data_sources s ORDER BY s.code");
 }
 public record NewSource(String code,String displayName,String host,int port,String databaseName,String readUsername,String passwordEnv,String password,String dbType){
  public NewSource(String code,String displayName,String host,int port,String databaseName,String readUsername,String passwordEnv,String password){
   this(code,displayName,host,port,databaseName,readUsername,passwordEnv,password,"MYSQL");
  }
  @Override public String toString(){return "NewSource[credentials=REDACTED]";}
 }
 static void validateSource(NewSource input){
  validateSource(input,true);
 }
 private static void validateSource(NewSource input,boolean requireCredential){
  if(input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写数据源信息");
  ReadDatasourceDialect.parse(input.dbType());
  if(input.displayName()==null||input.displayName().isBlank()||input.displayName().length()>128)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"数据源名称不能为空且不能超过 128 个字符");
  if(input.host()==null||!input.host().matches("[A-Za-z0-9.-]{1,253}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入有效的数据库 IP 或主机名");
  if(input.port()<1||input.port()>65535)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"端口必须在 1 到 65535 之间");
  if(input.readUsername()==null||!input.readUsername().matches("[A-Za-z0-9_]{1,80}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入有效的数据库用户名");
  if(input.code()==null||!input.code().matches("[a-zA-Z][a-zA-Z0-9_]{0,63}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"编码须以字母开头，仅包含字母、数字和下划线，最多 64 位");
  if(input.databaseName()==null||!input.databaseName().matches("[a-zA-Z][a-zA-Z0-9_]{0,63}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"数据库名须以字母开头，仅包含字母、数字和下划线，最多 64 位");
  boolean password=input.password()!=null&&!input.password().isEmpty();
  boolean env=input.passwordEnv()!=null&&!input.passwordEnv().isBlank();
  if((password&&env)||(requireCredential&&!password&&!env))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写数据库密码，或仅指定密码环境变量");
  if(password&&input.password().length()>4096)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"数据库密码过长");
  if(env&&!input.passwordEnv().matches("DATAGUARD_TARGET_[A-Z0-9_]{3,64}_PASSWORD"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"密码环境变量名须为 DATAGUARD_TARGET_名称_PASSWORD；直接输入密码请使用 password 字段");
 }
 @PostMapping("/sources")
 @org.springframework.transaction.annotation.Transactional
 public Map<String,Object> addSource(Authentication auth,@RequestBody NewSource input){
  Guard.requireAdmin(auth);
  validateSource(input);
  String db=Guard.identifier(input.databaseName());
  String code=Guard.identifier(input.code());
  String id=UUID.randomUUID().toString();
  String encrypted=input.password()!=null&&!input.password().isEmpty()?secrets.encrypt(id,input.password()):null;
  jdbc.update("INSERT INTO data_sources(id,code,display_name,host,port,database_name,read_username,password_env,encrypted_password,db_type,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,0)",
     id,code,input.displayName().strip(),input.host(),input.port(),db,input.readUsername(),encrypted==null?input.passwordEnv():null,encrypted,
     ReadDatasourceDialect.parse(input.dbType()).name());
  audit.log(auth.getName(),"SOURCE_CREATED","SOURCE",id,"SUCCESS","source disabled by default");
  return Map.of("id",id,"enabled",false);
 }
 @GetMapping("/sources/{id}")
 public Map<String,Object> detail(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  var rows=jdbc.queryForList("SELECT id,code,display_name displayName,host,port,database_name databaseName,read_username readUsername,db_type dbType,enabled,created_at createdAt FROM data_sources WHERE id=?",id.toString());
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"数据源不存在");
  return rows.get(0);
 }
 @PutMapping("/sources/{id}")
 @org.springframework.transaction.annotation.Transactional
 public Map<String,Object> edit(Authentication auth,@PathVariable UUID id,@RequestBody NewSource input){
  Guard.requireAdmin(auth);validateSource(input,false);
  var rows=jdbc.queryForList("SELECT enabled FROM data_sources WHERE id=? FOR UPDATE",id.toString());
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"数据源不存在");
  if(Boolean.TRUE.equals(rows.get(0).get("enabled"))||(rows.get(0).get("enabled") instanceof Number n&&n.intValue()!=0))throw new ResponseStatusException(HttpStatus.CONFLICT,"请先停用数据源再编辑连接配置");
  jdbc.update("UPDATE data_sources SET code=?,display_name=?,host=?,port=?,database_name=?,read_username=?,db_type=? WHERE id=?",input.code(),input.displayName().strip(),input.host(),input.port(),input.databaseName(),input.readUsername(),ReadDatasourceDialect.parse(input.dbType()).name(),id.toString());
  if(input.password()!=null&&!input.password().isEmpty())jdbc.update("UPDATE data_sources SET encrypted_password=?,password_env=NULL WHERE id=?",secrets.encrypt(id.toString(),input.password()),id.toString());
  else if(input.passwordEnv()!=null&&!input.passwordEnv().isBlank())jdbc.update("UPDATE data_sources SET password_env=?,encrypted_password=NULL WHERE id=?",input.passwordEnv(),id.toString());
  audit.log(auth.getName(),"SOURCE_UPDATED","SOURCE",id.toString(),"SUCCESS","disabled source configuration updated");
  return Map.of("id",id.toString(),"enabled",false);
 }
 @PostMapping("/sources/{id}/enable")
 public Map<String,Object> enable(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  int n=jdbc.update("UPDATE data_sources SET enabled=1 WHERE id=?",id.toString());
  if(n!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  audit.log(auth.getName(),"SOURCE_ENABLED","SOURCE",id.toString(),"SUCCESS","read-only");
  return Map.of("enabled",true);
 }
 @PostMapping("/sources/{id}/disable")
 public Map<String,Object> disable(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  int n=jdbc.update("UPDATE data_sources SET enabled=0 WHERE id=?",id.toString());
  if(n!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  audit.log(auth.getName(),"SOURCE_DISABLED","SOURCE",id.toString(),"SUCCESS","immediate");
  return Map.of("enabled",false);
 }
 @PostMapping("/sources/{id}/test")
 public Map<String,Object> testConnection(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  List<Map<String,Object>> rows=jdbc.queryForList("SELECT host,port,database_name,read_username,password_env,encrypted_password,db_type FROM data_sources WHERE id=?",id.toString());
  if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  Map<String,Object> source=rows.get(0);
  // Never allow an administrator's connection test to bypass outbound JDBC ACL.
  ReadSourceHostPolicy.requireAllowed(String.valueOf(source.get("host")));
  String secret=secrets.resolve(id.toString(),source);
  ReadDatasourceDialect dialect=ReadDatasourceDialect.parse(source.get("db_type"));
  String url=dialect.url(String.valueOf(source.get("host")),
    ((Number)source.get("port")).intValue(),String.valueOf(source.get("database_name")));
  Properties properties=new Properties();properties.setProperty("user",String.valueOf(source.get("read_username")));properties.setProperty("password",secret);
  try(var connection=DriverManager.getConnection(url,properties);var statement=connection.createStatement()){
   statement.setQueryTimeout(5);
   try(var result=statement.executeQuery("SELECT 1")){if(!result.next()||result.getInt(1)!=1)throw new SQLException("probe failed");}
  }catch(SQLException ex){
   audit.log(auth.getName(),"SOURCE_CONNECTION_TEST","SOURCE",id.toString(),"FAILED",ex.getClass().getSimpleName());
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"数据源连接失败，请检查网络、账号和只读权限");
  }
  audit.log(auth.getName(),"SOURCE_CONNECTION_TEST","SOURCE",id.toString(),"SUCCESS",dialect.name()+" SELECT 1");
  return Map.of("connected",true);
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
