package io.github.mcpdataguard.standalone;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Business source adapters. The platform's JdbcTemplate and Flyway ALWAYS use MySQL.
 * StarRocks FE speaks MySQL wire protocol; no PostgreSQL or multi-query fallback.
 */
enum ReadDatasourceDialect {
 MYSQL, STARROCKS;
 static ReadDatasourceDialect parse(Object raw) {
  String value=raw==null?"MYSQL":String.valueOf(raw).strip().toUpperCase(Locale.ROOT);
  try{return valueOf(value);}
  catch(IllegalArgumentException ex){
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only MYSQL or STARROCKS is supported");
  }
 }
 int defaultPort(){return this==MYSQL?3306:9030;}
 String url(String host,int port,String database) {
  if(host==null||!host.matches("[A-Za-z0-9.-]{1,253}")||port<1||port>65535)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid target host or port");
  Guard.identifier(database);
  // StarRocks is accessed via its MySQL-compatible FE query port. Require TLS
  // hostname verification; never silently downgrade to an unverified connection.
  return "jdbc:mysql://"+host+":"+port+"/"+database
    +"?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC&allowMultiQueries=false"
    +"&allowLoadLocalInfile=false&allowUrlInLocalInfile=false"
    +"&connectTimeout=4000&socketTimeout=7000";
 }
 void enforceReadOnly(Connection connection,String database,String table) throws SQLException {
  connection.setReadOnly(true);
  if(this==MYSQL)MysqlReadGrantPolicy.requireReadOnlyGrants(connection);
  else StarRocksReadGrantPolicy.requireDirectTableSelect(connection,database,table);
 }
 void begin(Connection connection) throws SQLException {
  if(this==MYSQL)connection.setAutoCommit(false);
 }
 void finish(Connection connection) throws SQLException {
  if(this==MYSQL)connection.rollback();
 }
}
