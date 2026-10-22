package io.github.mcpdataguard.sql;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.shardingsphere.sql.parser.api.SQLParserEngine;
import org.apache.shardingsphere.sql.parser.statement.core.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.statement.core.statement.dml.SelectStatement;
import org.apache.shardingsphere.sql.parser.statement.core.statement.SQLStatementVisitorEngine;
import org.apache.shardingsphere.sql.parser.statement.core.parser.SQLParserEngineFactory;

/**
 * Deliberately tiny, fail-closed SELECT subset. Not a generic SQL firewall.
 * Production extension requires an AST-based permission model, function catalog,
 * real read-only DB grants and adversarial dialect certification.
 */
public final class StrictReadOnlySql {
 private static final Pattern SIMPLE = Pattern.compile(
   "(?is)^SELECT\\s+([a-z_][a-z0-9_]*(?:\\s*,\\s*[a-z_][a-z0-9_]*)*)\\s+FROM\\s+([a-z_][a-z0-9_]*)(?:\\s+WHERE\\s+([a-z_][a-z0-9_]*)\\s*=\\s*\\?)?\\s+LIMIT\\s+([1-9][0-9]{0,3})$"
 );
 private StrictReadOnlySql() {}
 public static SqlVerdict analyze(String dialect, String sql, int parameterCount, int maxRows) {
   if (sql == null || sql.length() > 8192 || maxRows < 1 || maxRows > 1000) return SqlVerdict.deny("INVALID_INPUT");
   if (!"MYSQL".equalsIgnoreCase(dialect) && !"POSTGRESQL".equalsIgnoreCase(dialect)) return SqlVerdict.deny("DIALECT_NOT_CERTIFIED");
   if (sql.indexOf(';') >= 0 || sql.contains("--") || sql.contains("/*") || sql.contains("*/") || sql.indexOf('#') >= 0 || sql.indexOf('\\u0000') >= 0) return SqlVerdict.deny("MULTI_STATEMENT_OR_COMMENT");
   Matcher m = SIMPLE.matcher(sql.strip());
   if (!m.matches()) return SqlVerdict.deny("UNSUPPORTED_SQL_SHAPE");
   int bound = m.group(3) == null ? 0 : 1;
   if (parameterCount != bound || Integer.parseInt(m.group(4)) > maxRows) return SqlVerdict.deny("PARAMETERS_OR_ROW_LIMIT");
   // The strict input grammar prevents functions, UNION, CTE, SELECT INTO, subqueries,
   // writes, expressions, aliases and ordering, including volatile functions.
   try {
     String type = "MYSQL".equalsIgnoreCase(dialect) ? "MySQL" : "PostgreSQL";
     // This syntax must ALSO be recognized by the configured DB dialect parser.
     // ShardingSphere's exact visitor ABI is pinned and checked by Maven CI.
     SQLParserEngine parser = new SQLParserEngine(type, new org.apache.shardingsphere.infra.util.cache.CacheOption(128, 1024L));
     var ast = parser.parse(sql, false);
     SQLStatementVisitorEngine visitor = new SQLStatementVisitorEngine(type);
     SQLStatement statement = visitor.visit(ast);
     if (!(statement instanceof SelectStatement)) return SqlVerdict.deny("NOT_READ_ONLY");
   } catch (Exception e) { return SqlVerdict.deny("PARSER_UNCERTAIN"); }
   return new SqlVerdict(true, "SELECT", "STRICT_READ_SUBSET");
 }
 public static String table(String sql) {
   Matcher m = SIMPLE.matcher(sql.strip());
   if (!m.matches()) throw new IllegalArgumentException("SQL rejected");
   return m.group(2).toLowerCase(Locale.ROOT);
 }
 public static String[] columns(String sql) {
   Matcher m = SIMPLE.matcher(sql.strip());
   if (!m.matches()) throw new IllegalArgumentException("SQL rejected");
   return m.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", "").split(",");
 }
 public static String predicateColumn(String sql) {
   Matcher m = SIMPLE.matcher(sql.strip());
   if (!m.matches()) throw new IllegalArgumentException("SQL rejected");
   return m.group(3) == null ? null : m.group(3).toLowerCase(Locale.ROOT);
 }
}