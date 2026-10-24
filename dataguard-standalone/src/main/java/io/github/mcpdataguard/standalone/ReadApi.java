package io.github.mcpdataguard.standalone;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/read")
public class ReadApi {
 private final JdbcTemplate jdbc;
 private final AuditService audit;
 public ReadApi(JdbcTemplate jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}
 public record ReadQuery(String sourceId,String tableName,int limit){}
 static Object mask(Object value,boolean permitted){
  if(!permitted||value==null||value instanceof byte[])return "[REDACTED]";
  if(value instanceof Number || value instanceof Boolean)return value;
  if(value instanceof CharSequence text)
   return text.length()>256?text.subSequence(0,256).toString():text.toString();
  return "[REDACTED]";
 }
 public List<Map<String,Object>> mcpSources(String username){
  return jdbc.queryForList("""
   SELECT DISTINCT s.id,s.code,s.display_name AS displayName FROM data_sources s
   JOIN read_table_allowlist w ON w.source_id=s.id AND w.enabled=1
   JOIN approval_requests a ON a.source_id=s.id AND a.table_name=w.table_name
   JOIN app_users u ON u.id=a.requester_user_id
   WHERE s.enabled=1 AND a.status='APPROVED' AND a.expires_at>NOW(3)
     AND u.username=? AND u.enabled=1 AND u.must_change_password=0
   ORDER BY s.code LIMIT 100
   """,username);
 }
 public List<Map<String,Object>> mcpSchema(String username,String source){
  UUID id=UUID.fromString(source);
  return jdbc.queryForList("SELECT w.table_name AS tableName FROM read_table_allowlist w JOIN data_sources s ON s.id=w.source_id WHERE w.source_id=? AND w.enabled=1 AND s.enabled=1 AND EXISTS(SELECT 1 FROM approval_requests a JOIN app_users u ON u.id=a.requester_user_id WHERE a.source_id=w.source_id AND a.table_name=w.table_name AND a.status='APPROVED' AND a.expires_at>NOW(3) AND u.username=?) ORDER BY w.table_name LIMIT 100",id.toString(),username);
 }
 @PostMapping
 public Map<String,Object> read(Authentication auth,@RequestBody ReadQuery input){
  if(input==null||input.limit()<1||input.limit()>100)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Read limit must be 1..100");
  String table=Guard.identifier(input.tableName());
  String id=UUID.fromString(input.sourceId()).toString();
  String actor=jdbc.queryForObject("SELECT id FROM app_users WHERE username=? AND enabled=1",String.class,auth.getName());
  // A table must be BOTH explicitly allowlisted AND individually approved for this requester.
  List<Map<String,Object>> ds=jdbc.queryForList("""
    SELECT s.host,s.port,s.database_name,s.read_username,s.password_env
    FROM data_sources s JOIN read_table_allowlist w
      ON w.source_id=s.id AND w.table_name=? AND w.enabled=1
    WHERE s.id=? AND s.enabled=1 AND EXISTS(
       SELECT 1 FROM approval_requests a WHERE a.source_id=s.id AND
        a.table_name=? AND a.requester_user_id=? AND a.status='APPROVED' AND a.expires_at>NOW(3))
    """,table,id,table,actor);
  if(ds.size()!=1){
   audit.log(auth.getName(),"SQL_READ","TABLE",id+"/"+table,"DENIED","not allowlisted or approved");
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Table is not allowlisted and approved");
  }
  Map<String,Object> source=ds.get(0);
  String envName=(String)source.get("password_env");
  String password=System.getenv(envName);
  if(password==null||password.isBlank())
   throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Read-only credential not configured");
  String host=(String)source.get("host");
  String allowed=System.getenv().getOrDefault("DATAGUARD_READ_HOSTS","127.0.0.1,localhost");
  if(!Set.of(allowed.split(",")).contains(host))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Datasource host is not approved");
  String database=Guard.identifier((String)source.get("database_name"));
  String username=(String)source.get("read_username");
  int port=((Number)source.get("port")).intValue();
  String url="jdbc:mysql://"+host+":"+port+"/"+database+
     "?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC&allowMultiQueries=false&allowLoadLocalInfile=false&allowUrlInLocalInfile=false&socketTimeout=7000&connectTimeout=4000";
  Set<String> exposed=new HashSet<>(jdbc.query("""
    SELECT LOWER(column_name) FROM read_column_allowlist
    WHERE source_id=? AND table_name=? AND enabled=1
    """,(rs,i)->rs.getString(1),id,table));
  String sql="SELECT * FROM `"+table+"` LIMIT "+input.limit();
  // The target user MUST have only SELECT grants. Connection is read-only,
  // statement is read-only and any transport / audit error is fail-closed.
  List<Map<String,Object>> rows=new ArrayList<>();
  try(var connection=DriverManager.getConnection(url,username,password)){
   connection.setReadOnly(true);
   connection.setAutoCommit(false);
   MysqlReadGrantPolicy.requireReadOnlyGrants(connection);
   try(var stmt=connection.prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
    stmt.setQueryTimeout(5);
    stmt.setMaxRows(input.limit());
    try(var results=stmt.executeQuery()){
     var metadata=results.getMetaData();
     while(results.next()){
      Map<String,Object> row=new LinkedHashMap<>();
      for(int col=1;col<=metadata.getColumnCount();col++){
       String column=metadata.getColumnLabel(col);
       // Fail closed on sensitive columns: never return raw data from an
       // unclassified schema. The policy below deliberately redacts all fields.
       row.put(column,mask(results.getObject(col),
           exposed.contains(column.toLowerCase(Locale.ROOT))));
      }
      rows.add(row);
     }
    }
   }
   connection.rollback();
  }catch(java.sql.SQLException ex){
   audit.log(auth.getName(),"SQL_READ","TABLE",id+"/"+table,"ERROR","read failed");
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Datasource read failed");
  }
  audit.log(auth.getName(),"SQL_READ","TABLE",id+"/"+table,"SUCCESS","rows="+rows.size());
  return Map.of("decision","ALLOW","table",table,"rows",rows,"rowCount",rows.size(),
    "masking",exposed.isEmpty()?"REDACT_ALL_DEFAULT":"EXPLICIT_COLUMN_ALLOWLIST");
 }
}