package io.github.mcpdataguard.standalone;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Intentionally conservative StarRocks >=3 grant verification. A direct,
 * table-specific SELECT is required. Role inheritance, wildcard privileges,
 * write/system privileges and unrecognized grants fail closed. DBAs must
 * provision an isolated StarRocks principal for each authorized target scope.
 * This does not replace an independent least-privilege account audit.
 */
final class StarRocksReadGrantPolicy {
 private StarRocksReadGrantPolicy(){}
 static boolean onlyDirectTableSelect(List<String> grants,String database,String table) {
  if(grants==null||grants.isEmpty())return false;
  String object=Pattern.quote((Guard.identifier(database)+"."+Guard.identifier(table)).toUpperCase(Locale.ROOT));
  Pattern select=Pattern.compile("^GRANT SELECT ON (?:TABLE|VIEW|MATERIALIZED VIEW) "+object+" TO (?:USER )?[^;]+;?$");
  Pattern usage=Pattern.compile("^GRANT USAGE ON CATALOG DEFAULT_CATALOG TO (?:USER )?[^;]+;?$");
  boolean hasSelect=false;
  for(String raw:grants){
   if(raw==null||raw.isBlank())return false;
   String normalized=raw.replace("`","").replaceAll("\\s+"," ").strip().toUpperCase(Locale.ROOT);
   // Deny assignment to roles (even the default public role), privilege
   // inheritance and any grant options rather than trying to expand a graph.
   if(normalized.contains(" WITH GRANT OPTION"))return false;
   if(select.matcher(normalized).matches())hasSelect=true;
   else if(!usage.matcher(normalized).matches())return false;
  }
  return hasSelect;
 }
 static void requireDirectTableSelect(Connection connection,String database,String table) throws SQLException {
  List<String> grants=new ArrayList<>();
  try(var s=connection.createStatement();var rs=s.executeQuery("SHOW GRANTS FOR CURRENT_USER()")){
   int cols=rs.getMetaData().getColumnCount();
   if(cols!=1&&cols!=3)throw new SQLException("Unknown StarRocks grant format");
   while(rs.next()){
    String grant=rs.getString(cols);
    if(grant==null)throw new SQLException("StarRocks role grants not supported");
    grants.add(grant);
   }
  }
  if(!onlyDirectTableSelect(grants,database,table))
   throw new SQLException("StarRocks account must have direct table SELECT only");
 }
}
