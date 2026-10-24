package io.github.mcpdataguard.standalone;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
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
        a.table_name=? AND a.requester_user_id=? AND a.status='APPROVED')
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
  String sql="SELECT * FROM `"+table+"` LIMIT "+input.limit();
  // The target user MUST have only SELECT grants. Connection is read-only,
  // statement is read-only and any transport / audit error is fail-closed.
  List<Map<String,Object>> rows=new ArrayList<>();
  try(var connection=DriverManager.getConnection(url,username,password)){
   connection.setReadOnly(true);
   connection.setAutoCommit(false);
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
       row.put(column,"[REDACTED]");
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
  return Map.of("decision","ALLOW","table",table,"rows",rows,"rowCount",rows.size(),"masking","REDACT_ALL_DEFAULT");
 }
}