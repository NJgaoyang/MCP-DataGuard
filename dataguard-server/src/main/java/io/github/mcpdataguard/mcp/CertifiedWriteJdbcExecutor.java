package io.github.mcpdataguard.mcp;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Physically isolated, single-table, single-row PostgreSQL mutation primitive.
 * No public API. SQL must first be authenticated against its encrypted approval
 * and a separately HMAC-signed one-time authorization; this component applies
 * an independent exact-shape and database-privilege boundary.
 */
@Service
public class CertifiedWriteJdbcExecutor {
 private static final Pattern UPDATE=Pattern.compile(
   "(?i)^UPDATE\\s+([a-z][a-z0-9_]*)\\s+SET\\s+([a-z][a-z0-9_]*)\\s*=\\s*\\?"+
   "\\s+WHERE\\s+([a-z][a-z0-9_]*)\\s*=\\s*\\?$");
 private static final Pattern DELETE=Pattern.compile(
   "(?i)^DELETE\\s+FROM\\s+([a-z][a-z0-9_]*)\\s+WHERE"+
   "\\s+([a-z][a-z0-9_]*)\\s*=\\s*\\?$");
 private final Set<String> allowedHosts;
 public CertifiedWriteJdbcExecutor(@Value("${dataguard.write.allowed-hosts:}") String hosts){
  allowedHosts=Arrays.stream(hosts.split(",")).map(String::strip)
    .map(x->x.toLowerCase(Locale.ROOT)).filter(x->!x.isBlank())
    .collect(java.util.stream.Collectors.toUnmodifiableSet());
 }
 public record Target(String host,int port,String database,String user,String password){}
 public record Result(int affectedRows,String operation){}
 public Result execute(Target target,String sql,List<Object> values)throws Exception{
  if(target==null || target.host()==null ||
     !target.host().matches("[a-zA-Z0-9][a-zA-Z0-9.-]{1,252}") ||
     target.host().contains("..") || target.port()<1 || target.port()>65535 ||
     target.database()==null || !target.database().matches("[a-zA-Z_][a-zA-Z0-9_-]{0,62}") ||
     target.user()==null || target.user().isBlank() ||
     target.password()==null || target.password().isBlank() ||
     sql==null || sql.length()>8192 || values==null)
   throw new SecurityException("INVALID_WRITE_TARGET_OR_SQL");
  if(!allowedHosts.contains(target.host().toLowerCase(Locale.ROOT)))
   throw new SecurityException("WRITE_HOST_NOT_CERTIFIED");
  Matcher update=UPDATE.matcher(sql.strip()),delete=DELETE.matcher(sql.strip());
  String operation,table,predicate;
  if(update.matches()){
   operation="UPDATE";table=update.group(1);predicate=update.group(3);
   if(values.size()!=2)throw new SecurityException("WRITE_BINDS_MISMATCH");
  }else if(delete.matches()){
   operation="DELETE";table=delete.group(1);predicate=delete.group(2);
   if(values.size()!=1)throw new SecurityException("WRITE_BINDS_MISMATCH");
  }else throw new SecurityException("WRITE_SQL_SHAPE_DENIED");
  if(values.stream().anyMatch(v->v==null ||
      !(v instanceof Number || v instanceof Boolean || v instanceof String) ||
      String.valueOf(v).length()>2048))
   throw new SecurityException("UNSAFE_WRITE_PARAMETERS");
  Properties credentials=new Properties();
  credentials.setProperty("user",target.user());
  credentials.setProperty("password",target.password());
  String url="jdbc:postgresql://"+target.host()+":"+target.port()+"/"+
     target.database()+"?sslmode=verify-full&connectTimeout=3&socketTimeout=5";
  try(Connection connection=DriverManager.getConnection(url,credentials)){
   connection.setAutoCommit(false);
   connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
   try{
    // The login cannot administer the database, create objects, or modify
    // any user table other than the one explicitly approved.
    try(PreparedStatement rights=connection.prepareStatement("""
       SELECT NOT EXISTS(
        SELECT 1 FROM pg_roles r WHERE
          (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR
            r.rolreplication OR r.rolbypassrls)
          AND pg_has_role(current_user,r.oid,'MEMBER')
       ) AND NOT has_database_privilege(current_user,current_database(),'CREATE')
       AND NOT EXISTS (
         SELECT 1 FROM pg_namespace n WHERE n.nspname NOT LIKE 'pg_%'
          AND n.nspname<>'information_schema'
          AND has_schema_privilege(current_user,n.oid,'CREATE')
       ) AND NOT EXISTS (
         SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'
           AND c.relkind IN ('r','p','f','v','m')
           AND (has_table_privilege(current_user,c.oid,'INSERT') OR
                has_table_privilege(current_user,c.oid,'UPDATE') OR
                has_table_privilege(current_user,c.oid,'DELETE') OR
                has_table_privilege(current_user,c.oid,'TRUNCATE') OR
                has_table_privilege(current_user,c.oid,'TRIGGER'))
           AND (n.nspname<>'public' OR c.relname<>?)
       ) AND EXISTS (
         SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='public' AND c.relname=? AND c.relkind='r'
           AND NOT c.relrowsecurity
           AND NOT pg_has_role(current_user,c.relowner,'MEMBER')
           AND has_table_privilege(current_user,c.oid,?)
           AND NOT has_table_privilege(current_user,c.oid,'TRUNCATE')
           AND NOT has_table_privilege(current_user,c.oid,'TRIGGER')
           AND NOT EXISTS(
            SELECT 1 FROM pg_trigger t WHERE t.tgrelid=c.oid AND NOT t.tgisinternal
           )
           AND NOT EXISTS(
            SELECT 1 FROM pg_constraint f
             WHERE f.contype='f' AND f.confrelid=c.oid
           )
       )
       """)){
     rights.setString(1,table);
     rights.setString(2,table);
     rights.setString(3,operation);
     try(ResultSet rs=rights.executeQuery()){
      if(!rs.next()||!rs.getBoolean(1))
       throw new SecurityException("WRITE_PHYSICAL_ROLE_NOT_CERTIFIED");
     }
    }
    try(Statement budget=connection.createStatement()){
     budget.execute("SET LOCAL statement_timeout = '5000ms'");
     budget.execute("SET LOCAL lock_timeout = '1500ms'");
    }
    try(PreparedStatement statement=connection.prepareStatement(sql.strip())){
     statement.setQueryTimeout(5);
     for(int i=0;i<values.size();i++)statement.setObject(i+1,values.get(i));
     int count=statement.executeUpdate();
     if(count!=1)throw new SecurityException("WRITE_MUST_AFFECT_EXACTLY_ONE_ROW");
     connection.commit();
     return new Result(count,operation);
    }
   }catch(Exception error){
    connection.rollback();
    throw error;
   }
  }
 }
}
