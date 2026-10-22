package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Store a secret POINTER, not credentials. Only operators may set env vars.
 * Test connection requires an explicit host allowlist and strict TLS validation;
 * successful connectivity does NOT certify SQL execution permissions.
 */
@RestController
@RequestMapping("/api/v1/datasources")
public class DatasourceConnectionController {
 private static final Pattern ENV_REF=Pattern.compile("DG_TARGET_[A-Z0-9_]{3,64}");
 private static final Pattern DNS_HOST=Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{1,252}");
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 private final Set<String> allowedHosts;
 public DatasourceConnectionController(JdbcTemplate jdbc,UserPrincipalResolver users,
     @Value("${dataguard.connection-test.allowed-hosts:}") String hosts) {
  this.jdbc=jdbc;this.users=users;
  Set<String> s=new HashSet<>();
  Arrays.stream(hosts.split(",")).map(String::strip).filter(v->!v.isEmpty())
    .forEach(v->s.add(v.toLowerCase(Locale.ROOT)));
  allowedHosts=Set.copyOf(s);
 }
 public record ReferenceInput(String secretRef){}
 private UserPrincipal principal(Jwt jwt,String perm){
  UserPrincipal actor=users.requireUser(jwt);
  users.requirePermission(actor,perm);
  return actor;
 }
 @PutMapping("/{datasourceId}/read-secret")
 @Transactional
 public Map<String,String> setSecret(@AuthenticationPrincipal Jwt jwt,
   @PathVariable UUID datasourceId,@RequestBody ReferenceInput input) {
  UserPrincipal actor=principal(jwt,"datasource:secret:write");
  if(input==null || input.secretRef()==null || !ENV_REF.matcher(input.secretRef()).matches())
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid managed secret reference");
  Boolean exists=jdbc.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM dg_datasource WHERE org_id=? AND id=? AND status='DISABLED')
   """,Boolean.class,actor.orgId(),datasourceId);
  if(!Boolean.TRUE.equals(exists))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Datasource must be disabled");
  jdbc.update("""
   INSERT INTO dg_datasource_secret(id,org_id,datasource_id,purpose,provider,secret_ref)
   VALUES(?,?,?,'READ','ENV',?)
   ON CONFLICT(org_id,datasource_id,purpose)
   DO UPDATE SET provider='ENV',secret_ref=excluded.secret_ref,rotated_at=now()
   """,UUID.randomUUID(),actor.orgId(),datasourceId,input.secretRef());
  event(actor,"READ_SECRET_REF_UPDATED",datasourceId);
  return Map.of("provider","ENV","secretRef",input.secretRef());
 }
 @GetMapping("/{datasourceId}/read-secret")
 public Map<String,String> getSecret(@AuthenticationPrincipal Jwt jwt,
   @PathVariable UUID datasourceId) {
  UserPrincipal actor=principal(jwt,"datasource:secret:write");
  List<Map<String,String>> rows=jdbc.query("""
   SELECT s.provider,s.secret_ref FROM dg_datasource_secret s
   JOIN dg_datasource d ON d.org_id=s.org_id AND d.id=s.datasource_id
   WHERE s.org_id=? AND s.datasource_id=? AND s.purpose='READ'
   """,(rs,i)->Map.of("provider",rs.getString("provider"),
     "secretRef",rs.getString("secret_ref")),actor.orgId(),datasourceId);
  if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Secret reference not found");
  return rows.get(0);
 }
 @PostMapping("/{datasourceId}/connection-test")
 public Map<String,Object> test(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID datasourceId) {
  UserPrincipal actor=principal(jwt,"datasource:test");
  List<Target> rows=jdbc.query("""
   SELECT d.host,d.port,d.database_name,d.db_type,s.provider,s.secret_ref
   FROM dg_datasource d
   JOIN dg_datasource_secret s ON s.org_id=d.org_id AND s.datasource_id=d.id
   WHERE d.org_id=? AND d.id=? AND s.purpose='READ' AND d.status='DISABLED'
   """,(rs,i)->new Target(rs.getString(1),rs.getInt(2),rs.getString(3),
       rs.getString(4),rs.getString(5),rs.getString(6)),actor.orgId(),datasourceId);
  if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Read secret or disabled datasource required");
  Target target=rows.get(0);
  if(!allowedHosts.contains(target.host().toLowerCase(Locale.ROOT)) ||
     !DNS_HOST.matcher(target.host()).matches() || target.host().contains("..") ||
     !ENV_REF.matcher(target.ref()).matches() || !"ENV".equals(target.provider()))
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Target is not in operator allowlist");
  String username=System.getenv(target.ref()+"_USER");
  String password=System.getenv(target.ref()+"_PASSWORD");
  if(username==null || username.isBlank() || password==null || password.isBlank())
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Secret not configured on server");
  String database=target.database();
  if(database==null || !database.matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,62}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Database name required for test");
  String url=switch(target.type()){
   case "POSTGRESQL" -> "jdbc:postgresql://"+target.host()+":"+target.port()+"/"+database+
     "?sslmode=verify-full&connectTimeout=3&socketTimeout=5";
   case "MYSQL" -> "jdbc:mysql://"+target.host()+":"+target.port()+"/"+database+
     "?sslMode=VERIFY_IDENTITY&connectTimeout=3000&socketTimeout=5000";
   default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dialect unavailable");
  };
  // Record attempts but never log passwords, credentials, JDBC URL, or returned diagnostics.
  event(actor,"DATASOURCE_CONNECTION_TEST",datasourceId);
  Properties props=new Properties();
  props.setProperty("user",username);
  props.setProperty("password",password);
  try(Connection conn=DriverManager.getConnection(url,props)){
   conn.setReadOnly(true);
   try(Statement st=conn.createStatement()){
    st.setQueryTimeout(5);
    try(ResultSet rs=st.executeQuery("SELECT 1")){
     if(!rs.next() || rs.getInt(1)!=1)throw new SQLException("Unexpected probe response");
    }
   }
   return Map.of("connected",true,"tls","VERIFY_IDENTITY","sqlExecutionEnabled",false);
  }catch(SQLException e){
   // Deliberately don't expose driver error details or network metadata.
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Connection probe failed");
  }
 }
 private void event(UserPrincipal actor,String operation,UUID id){
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
   VALUES (?,?,?,?,'DATASOURCE',?)
   """,UUID.randomUUID(),actor.orgId(),actor.userId().toString(),operation,id.toString());
 }
 private record Target(String host,int port,String database,String type,String provider,String ref){}
}
