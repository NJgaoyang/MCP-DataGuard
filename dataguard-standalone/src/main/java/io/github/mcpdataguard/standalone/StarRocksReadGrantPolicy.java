package io.github.mcpdataguard.standalone;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Intentionally conservative StarRocks >=3 grant verification. A direct,
 * table-specific SELECT is required. Non-public role inheritance, wildcard privileges,
 * write/system privileges and unrecognized grants fail closed. DBAs must
 * provision an isolated StarRocks principal for each authorized target scope.
 * This does not replace an independent least-privilege account audit.
 */
final class StarRocksReadGrantPolicy {
 private StarRocksReadGrantPolicy(){}
 static boolean onlyDirectTableSelect(List<String> grants,String database,String table) {
  if(grants==null||grants.isEmpty())return false;
  String databaseName=Guard.identifier(database).toUpperCase(Locale.ROOT);
  String object=Pattern.quote((databaseName+"."+Guard.identifier(table).toUpperCase(Locale.ROOT)));
  Pattern select=Pattern.compile("^GRANT SELECT ON TABLE "+object+" TO (?:USER )?[^;]+;?$");
  // One read-only credential may serve several explicitly table-granted
  // tables of the same database. The gateway still checks the platform
  // allowlist, approval and owner/Agent grants for EACH requested table.
  Pattern otherTableSelect=Pattern.compile("^GRANT SELECT ON TABLE "
     +Pattern.quote(databaseName)+"\\.[A-Z][A-Z0-9_]{0,63} TO (?:USER )?[^;]+;?$");
  Pattern usage=Pattern.compile("^GRANT USAGE ON CATALOG DEFAULT_CATALOG TO (?:USER )?[^;]+;?$");
  boolean hasSelect=false;
  for(String raw:grants){
   if(raw==null||raw.isBlank())return false;
   String normalized=raw.replace("`","").replaceAll("\\s+"," ").strip().toUpperCase(Locale.ROOT);
   // Deny assignment to roles (even the default public role), privilege
   // inheritance and any grant options rather than trying to expand a graph.
   if(normalized.contains(" WITH GRANT OPTION"))return false;
   if(select.matcher(normalized).matches())hasSelect=true;
   else if(!otherTableSelect.matcher(normalized).matches()
     && !usage.matcher(normalized).matches()
     && !normalized.replace("'PUBLIC'","PUBLIC").matches("^GRANT PUBLIC TO [^;]+;?$"))
     return false;
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
  // StarRocks activates the built-in public role for every user. It can be
  // modified by DBAs: do not trust it until its grants have also been checked.
  if(grants.stream().anyMatch(g->g!=null && g.toUpperCase(Locale.ROOT).contains("PUBLIC"))){
   try(var s=connection.createStatement();var rs=s.executeQuery("SHOW GRANTS FOR ROLE public")){
    if(rs.next())throw new SQLException("StarRocks public role carries privileges");
   }
  }
  if(!onlyDirectTableSelect(grants,database,table))
   throw new SQLException("StarRocks account must have direct table SELECT only");
 }
}
