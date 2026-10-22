package io.github.mcpdataguard.sql;

import java.util.Locale;
import org.apache.shardingsphere.sql.parser.api.SQLParserEngine;
import org.apache.shardingsphere.infra.util.cache.CacheOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Syntax shape check only. A regex MUST NOT grant database access.
 * All operations remain denied until a dialect AST analyzer, verified Agent
 * authorization, field grants and physical read-only credentials are in place. */
public final class StrictReadOnlySql {
 private static final Pattern SIMPLE=Pattern.compile(
   "(?is)^SELECT\\s+([a-z_][a-z0-9_]*(?:\\s*,\\s*[a-z_][a-z0-9_]*)*)" +
   "\\s+FROM\\s+([a-z_][a-z0-9_]*)" +
   "(?:\\s+WHERE\\s+([a-z_][a-z0-9_]*)\\s*=\\s*\\?)?" +
   "\\s+LIMIT\\s+([1-9][0-9]{0,2})$");
 private StrictReadOnlySql() {}
 private static Matcher checked(String sql) {
  if(sql==null || sql.length()>8192 || sql.isBlank() ||
     sql.contains(";") || sql.contains("--") || sql.contains("/*") ||
     sql.contains("*/") || sql.contains("#") || sql.indexOf('\0')>=0)
   throw new IllegalArgumentException("UNSUPPORTED_SQL_SHAPE");
  Matcher m=SIMPLE.matcher(sql.strip());
  if(!m.matches()) throw new IllegalArgumentException("UNSUPPORTED_SQL_SHAPE");
  return m;
 }
 public static SqlVerdict analyze(String dialect,String sql,int parameterCount,int maxRows) {
  if(!"MYSQL".equalsIgnoreCase(dialect)&&!"POSTGRESQL".equalsIgnoreCase(dialect))
   return SqlVerdict.deny("DIALECT_NOT_CERTIFIED");
  if(maxRows<1||maxRows>1000||parameterCount<0) return SqlVerdict.deny("INVALID_INPUT");
  try {
   // Run the parser for the datasource's *actual* dialect. The existing
   // strict grammar still rejects expressions / functions / nested statements.
   SQLParserEngine parser=new SQLParserEngine(
     "MYSQL".equalsIgnoreCase(dialect)?"MySQL":"PostgreSQL",
     new CacheOption(128,1024L));
   parser.parse(sql,false);
   Matcher m=checked(sql);
   if(parameterCount!=(m.group(3)==null?0:1) || Integer.parseInt(m.group(4))>maxRows)
    return SqlVerdict.deny("PARAMETERS_OR_ROW_LIMIT");
   return new SqlVerdict(false,"SELECT","AST_AND_AUTHORIZATION_NOT_CERTIFIED");
  }catch(RuntimeException e){return SqlVerdict.deny("UNSUPPORTED_SQL_SHAPE");}
 }
 public static String table(String sql){return checked(sql).group(2).toLowerCase(Locale.ROOT);}
 public static String[] columns(String sql){return checked(sql).group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+","").split(",");}
 public static String predicateColumn(String sql){
  String v=checked(sql).group(3);
  return v==null?null:v.toLowerCase(Locale.ROOT);
 }
}
