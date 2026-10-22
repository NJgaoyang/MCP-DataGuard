package io.github.mcpdataguard.sql;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StrictReadOnlySqlTest {
 @Test void allQueriesStayDeniedUntilCertified(){
  var verdict=StrictReadOnlySql.analyze("MYSQL","SELECT id FROM users LIMIT 10",0,50);
  assertFalse(verdict.allowed());
  assertEquals("SELECT",verdict.operation());
  assertEquals("AST_AND_AUTHORIZATION_NOT_CERTIFIED",verdict.reason());
 }
 @Test void rejectsDangerousSql(){
  for(String sql:new String[]{
   "DELETE FROM users","SELECT id FROM users LIMIT 10;DELETE FROM users",
   "SELECT * FROM users LIMIT 10","SELECT id FROM users WHERE id=1 OR 1=1 LIMIT 10",
   "SELECT id FROM users /*comment*/ LIMIT 10","SELECT pg_sleep(4) FROM users LIMIT 10",
   "WITH cte AS (DELETE FROM users) SELECT id FROM users LIMIT 10"
  }) assertFalse(StrictReadOnlySql.analyze("POSTGRESQL",sql,0,50).allowed(),sql);
 }
 @Test void validatesBindAndLimit(){
  assertFalse(StrictReadOnlySql.analyze("MYSQL","SELECT id FROM users WHERE id = ? LIMIT 10",0,50).allowed());
  assertFalse(StrictReadOnlySql.analyze("MYSQL","SELECT id FROM users LIMIT 999",0,50).allowed());
  assertFalse(StrictReadOnlySql.analyze("MYSQL",null,0,50).allowed());
  assertFalse(StrictReadOnlySql.analyze("ORACLE","SELECT id FROM users LIMIT 10",0,50).allowed());
  assertEquals("users",StrictReadOnlySql.table("SELECT id FROM users LIMIT 10"));
 }
}
