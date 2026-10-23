package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.sql.SqlVerdict;
import io.github.mcpdataguard.sql.StrictReadOnlySql;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Isolated PostgreSQL-only execution primitive. NOT connected to the MCP tools.
 * Operator must provision physically read-only credentials and a verified TLS host.
 * The caller must complete Agent ACL, schema and mask checks before invocation.
 */
@Service
public final class IsolatedReadOnlyExecutor {
 private final Set<String> allowedHosts;
 private final CertifiedMasker masker;
 public IsolatedReadOnlyExecutor(String hosts){this(hosts,new CertifiedMasker(""));}
 @org.springframework.beans.factory.annotation.Autowired
 public IsolatedReadOnlyExecutor(@Value("${dataguard.readonly.allowed-hosts:}") String hosts,
     CertifiedMasker masker){
  this.masker=masker;
  allowedHosts=Arrays.stream(hosts.split(",")).map(String::strip)
   .map(v->v.toLowerCase(Locale.ROOT)).filter(v->!v.isEmpty())
   .collect(java.util.stream.Collectors.toUnmodifiableSet());
 }
 public record Target(String host,int port,String database,String username,String password){}
 public record Request(String sql,List<Object> parameters,Set<String> exactAllowedColumns,
    int maxRows,int maxBytes,int timeoutSeconds,Map<String,String> columnMasks,
    String maskDomain){
  public Request(String sql,List<Object> parameters,Set<String> columns,
      int rows,int bytes,int timeout){
   this(sql,parameters,columns,rows,bytes,timeout,Map.of(),"");
  }
  public Request(String sql,List<Object> parameters,Set<String> columns,
      int rows,int bytes,int timeout,Map<String,String> masks){
   this(sql,parameters,columns,rows,bytes,timeout,masks,"");
  }
 }
 public record Result(List<String> columns,List<List<Object>> rows,int returnedBytes){}

 public boolean hashMaskConfigured(){return masker.hashEnabled();}
 public Result execute(Target target,Request request) throws SQLException {
  validate(target,request);
  if(!allowedHosts.contains(target.host().toLowerCase(Locale.ROOT)))
   throw new SecurityException("TARGET_NOT_ALLOWLISTED");
  List<Object> parameters=request.parameters()==null?List.of():request.parameters();
  SqlVerdict verdict=StrictReadOnlySql.analyze("POSTGRESQL",request.sql(),parameters.size(),
    Math.min(100,request.maxRows()));
  if(!"SELECT".equals(verdict.operation()))
   throw new SecurityException("SQL_SHAPE_DENIED");
  String[] projection=StrictReadOnlySql.columns(request.sql());
  Set<String> used=new HashSet<>(List.of(projection));
  String predicate=StrictReadOnlySql.predicateColumn(request.sql());
  if(predicate!=null)used.add(predicate);
  if(request.exactAllowedColumns()==null ||
     !request.exactAllowedColumns().containsAll(used))
   throw new SecurityException("COLUMN_GRANT_DENIED");
  String url="jdbc:postgresql://"+target.host()+":"+target.port()+"/"+target.database()+
    "?sslmode=verify-full&connectTimeout=3&socketTimeout=5";
  Properties credentials=new Properties();
  credentials.setProperty("user",target.username());
  credentials.setProperty("password",target.password());
  try(Connection connection=DriverManager.getConnection(url,credentials)){
   connection.setReadOnly(true);
   connection.setAutoCommit(false);
   try(Statement check=connection.createStatement();
       ResultSet status=check.executeQuery("SHOW transaction_read_only")){
    if(!status.next() || !"on".equalsIgnoreCase(status.getString(1)))
     throw new SecurityException("PHYSICAL_READ_ONLY_REQUIRED");
   }
   // JDBC readOnly is only a transaction hint. Refuse powerful or writable
   // server identities, including privileges inherited through member roles.
   try(Statement rights=connection.createStatement();
       ResultSet verified=rights.executeQuery("""
         SELECT NOT EXISTS (
          SELECT 1 FROM pg_roles r
          WHERE (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR
                 r.rolreplication OR r.rolbypassrls)
            AND pg_has_role(current_user,r.oid,'MEMBER')
         ) AND NOT EXISTS (
          SELECT 1 FROM pg_class c
          JOIN pg_namespace ns ON ns.oid=c.relnamespace
          WHERE ns.nspname NOT LIKE 'pg_%' AND ns.nspname<>'information_schema'
            AND c.relkind IN ('r','p','f','v','m')
            AND (has_table_privilege(current_user,c.oid,'INSERT')
              OR has_table_privilege(current_user,c.oid,'UPDATE')
              OR has_table_privilege(current_user,c.oid,'DELETE')
              OR has_table_privilege(current_user,c.oid,'TRUNCATE')
              OR has_table_privilege(current_user,c.oid,'TRIGGER'))
         ) AND NOT EXISTS (
          SELECT 1 FROM pg_namespace n WHERE n.nspname NOT LIKE 'pg_%'
            AND n.nspname<>'information_schema'
            AND has_schema_privilege(current_user,n.oid,'CREATE')
         ) AND NOT has_database_privilege(current_user,current_database(),'CREATE')
         """)){
    if(!verified.next() || !verified.getBoolean(1))
     throw new SecurityException("PHYSICAL_ROLE_NOT_READ_ONLY");
   }
   // A server-side read-only role is still required: JDBC flags are not an ACL.
   try(PreparedStatement statement=connection.prepareStatement(request.sql(),
        ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
    statement.setQueryTimeout(request.timeoutSeconds());
    statement.setMaxRows(request.maxRows());
    statement.setFetchSize(Math.min(request.maxRows(),50));
    for(int i=0;i<parameters.size();i++)statement.setObject(i+1,parameters.get(i));
    try(ResultSet rs=statement.executeQuery()){
     ResultSetMetaData meta=rs.getMetaData();
     if(meta.getColumnCount()!=projection.length)
      throw new SecurityException("UNEXPECTED_RESULT_COLUMNS");
     List<String> columns=new ArrayList<>();
     for(int i=1;i<=meta.getColumnCount();i++){
      String label=meta.getColumnLabel(i);
      if(!label.equalsIgnoreCase(projection[i-1]))
       throw new SecurityException("UNEXPECTED_RESULT_COLUMN");
      columns.add(label);
     }
     List<List<Object>> rows=new ArrayList<>();
     int bytes=0;
     while(rs.next()){
      if(rows.size()>=request.maxRows())throw new SecurityException("RESULT_ROWS_EXCEEDED");
      List<Object> row=new ArrayList<>();
      for(int i=1;i<=columns.size();i++){
       Object value=rs.getObject(i);
       // Conservative result policy: redact all non-null fields by default.
       // A certified masking engine is needed before plaintext output is enabled.
       Object output=masker.mask(value,request.columnMasks()==null?"REDACT":
          request.columnMasks().getOrDefault(columns.get(i-1),"REDACT"),
          request.maskDomain());
       bytes+=String.valueOf(output).getBytes(StandardCharsets.UTF_8).length;
       if(bytes>request.maxBytes())throw new SecurityException("RESULT_BYTES_EXCEEDED");
       row.add(output);
      }
      rows.add(java.util.Collections.unmodifiableList(new ArrayList<>(row)));
     }
     connection.rollback();
     return new Result(List.copyOf(columns),java.util.Collections.unmodifiableList(rows),bytes);
    }
   }finally{connection.rollback();}
  }
 }
 static Object maskValue(Object value){
  return maskValue(value,"REDACT");
 }
 static Object maskValue(Object value,String algorithm){
  if(value==null)return null;
  if("NULLIFY".equals(algorithm))return null;
  if("REDACT".equals(algorithm))return "[REDACTED]";
  throw new SecurityException("UNSUPPORTED_MASK_ALGORITHM");
 }
 private static void validate(Target target,Request r){
  if(target==null || r==null ||
    target.host()==null || !target.host().matches("[a-zA-Z0-9][a-zA-Z0-9.-]{1,252}") ||
    target.host().contains("..") ||
    target.port()<1 || target.port()>65535 ||
    target.database()==null || !target.database().matches("[a-zA-Z0-9_][a-zA-Z0-9_-]{0,62}") ||
    target.username()==null || target.username().isBlank() ||
    target.password()==null || target.password().isBlank() ||
    r.maxRows()<1 || r.maxRows()>100 || r.maxBytes()<1 || r.maxBytes()>1048576 ||
    r.timeoutSeconds()<1 || r.timeoutSeconds()>10 ||
    r.sql()==null || r.sql().length()>8192 ||
    (r.parameters()!=null && (r.parameters().size()>1 ||
      r.parameters().stream().anyMatch(p->p!=null && !(p instanceof String ||
        p instanceof Number || p instanceof Boolean)))))
   throw new IllegalArgumentException("UNSAFE_READ_REQUEST");
 }
}
