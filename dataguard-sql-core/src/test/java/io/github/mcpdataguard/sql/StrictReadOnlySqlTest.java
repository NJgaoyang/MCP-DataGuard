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
 @Test void validatesConcreteDialectAstButNeverGrantsExecution(){
  for(String dialect:new String[]{"POSTGRESQL","MYSQL"}){
   var ok=StrictReadOnlySql.analyze(dialect,
     "SELECT id,account_id FROM ledger WHERE account_id = ? LIMIT 5",1,10);
   assertFalse(ok.allowed());
   assertEquals("SELECT",ok.operation());
   assertEquals("AST_AND_AUTHORIZATION_NOT_CERTIFIED",ok.reason());
   assertArrayEquals(new String[]{"id","account_id"},
     StrictReadOnlySql.columns("SELECT id,account_id FROM ledger WHERE account_id = ? LIMIT 5"));
   assertEquals("ledger",StrictReadOnlySql.table(
     "SELECT id,account_id FROM ledger WHERE account_id = ? LIMIT 5"));
  }
 }
 @Test void rejectsAstThatAddsUnsafeClausesOrProjectionExpression(){
  for(String sql:new String[]{
   "SELECT id FROM customers FOR UPDATE LIMIT 5",
   "SELECT id FROM customers ORDER BY id LIMIT 5",
   "SELECT id FROM customers UNION SELECT id FROM admin LIMIT 5",
   "SELECT id, count(*) FROM customers LIMIT 5",
   "SELECT id FROM customers JOIN admin ON customers.id=admin.id LIMIT 5",
   "SELECT id FROM (SELECT id FROM admin) x LIMIT 5",
   "SELECT id INTO TEMP TABLE copied FROM customers LIMIT 5",
   "SELECT id AS exposed FROM customers LIMIT 5",
   "SELECT id FROM customers LIMIT 5 OFFSET 1000",
   "SELECT id FROM customers LIMIT 5; DROP TABLE customers"
  }){
   var verdict=StrictReadOnlySql.analyze("POSTGRESQL",sql,0,10);
   assertFalse(verdict.allowed(),sql);
   assertNotEquals("AST_AND_AUTHORIZATION_NOT_CERTIFIED",verdict.reason(),sql);
  }
 }
 @Test void validatesBindAndLimit(){
  assertFalse(StrictReadOnlySql.analyze("MYSQL","SELECT id FROM users WHERE id = ? LIMIT 10",0,50).allowed());
  assertFalse(StrictReadOnlySql.analyze("MYSQL","SELECT id FROM users LIMIT 999",0,50).allowed());
  assertFalse(StrictReadOnlySql.analyze("MYSQL",null,0,50).allowed());
  assertFalse(StrictReadOnlySql.analyze("ORACLE","SELECT id FROM users LIMIT 10",0,50).allowed());
  assertEquals("users",StrictReadOnlySql.table("SELECT id FROM users LIMIT 10"));
 }
}
