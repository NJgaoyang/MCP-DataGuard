package io.github.mcpdataguard.sql;

import java.util.Locale;
import org.apache.shardingsphere.sql.parser.engine.api.SQLParserEngine;
import org.apache.shardingsphere.sql.parser.engine.api.CacheOption;
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
   // Parse into a concrete dialect AST, not merely a syntax-success flag.
   var statement=new org.apache.shardingsphere.sql.parser.engine.api.SQLStatementVisitorEngine(
     "MYSQL".equalsIgnoreCase(dialect)?"MySQL":"PostgreSQL").visit(parser.parse(sql,false));
   if(!(statement instanceof org.apache.shardingsphere.sql.parser.statement.core.statement.type.dml.SelectStatement select))
    return SqlVerdict.deny("NOT_A_SELECT_AST");
   if(select.getWith().isPresent() || select.getInto().isPresent() ||
      select.getLock().isPresent() || select.getCombine().isPresent() ||
      select.getWindow().isPresent() || select.getHaving().isPresent() ||
      select.getGroupBy().isPresent() || select.getOutfile().isPresent() ||
      select.getOrderBy().isPresent())
    return SqlVerdict.deny("UNSAFE_SELECT_AST");
   Matcher m=checked(sql); // Restricts expressions/functions even within valid SELECT AST.
   if(select.getFrom().isEmpty() ||
      !(select.getFrom().get() instanceof org.apache.shardingsphere.sql.parser.statement.core.segment.generic.table.SimpleTableSegment table))
    return SqlVerdict.deny("MULTI_TABLE_OR_SUBQUERY");
   if(table.getOwner().isPresent() || table.getAlias().isPresent() ||
      !table.getTableName().getIdentifier().getValue().equalsIgnoreCase(m.group(2)))
    return SqlVerdict.deny("TABLE_AST_MISMATCH");
   var projections=select.getProjections().getProjections();
   String[] expectedColumns=m.group(1).replaceAll("\\\\s+","").split(",");
   if(select.getProjections().isDistinctRow() || projections.size()!=expectedColumns.length)
    return SqlVerdict.deny("PROJECTION_AST_MISMATCH");
   for(int i=0;i<projections.size();i++){
    if(!(projections.get(i) instanceof org.apache.shardingsphere.sql.parser.statement.core.segment.dml.item.ColumnProjectionSegment c) ||
       c.getAlias().isPresent() ||
       !c.getColumn().getIdentifier().getValue().equalsIgnoreCase(expectedColumns[i]))
     return SqlVerdict.deny("PROJECTION_AST_MISMATCH");
   }
   if(select.getLimit().isEmpty() || select.getLimit().get().getRowCount().isEmpty() ||
      !(select.getLimit().get().getRowCount().get() instanceof
        org.apache.shardingsphere.sql.parser.statement.core.segment.dml.pagination.NumberLiteralPaginationValueSegment literal) ||
      literal.getValue()==null || literal.getValue()!=Integer.parseInt(m.group(4)))
    return SqlVerdict.deny("LIMIT_AST_MISMATCH");
   if(parameterCount!=(m.group(3)==null?0:1) || Integer.parseInt(m.group(4))>maxRows ||
      (m.group(3)==null ? select.getWhere().isPresent():select.getWhere().isEmpty()))
    return SqlVerdict.deny("PARAMETERS_OR_ROW_LIMIT");
   // AST validation never by itself authorizes a physical database connection.
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
