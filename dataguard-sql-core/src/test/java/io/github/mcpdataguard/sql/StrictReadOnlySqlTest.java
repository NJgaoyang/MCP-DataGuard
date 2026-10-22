package io.github.mcpdataguard.sql;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StrictReadOnlySqlTest {
 @Test void refusesUnknownAndMultiStatements() {
  assertFalse(StrictReadOnlySql.analyze("MYSQL", "DELETE FROM users", 0, 50).allowed());
  assertFalse(StrictReadOnlySql.analyze("MYSQL", "SELECT id FROM users LIMIT 10; DELETE FROM users", 0, 50).allowed());
  assertFalse(StrictReadOnlySql.analyze("MYSQL", "SELECT id FROM users WHERE id=1 OR 1=1 LIMIT 10", 0, 50).allowed());
  assertFalse(StrictReadOnlySql.analyze("MYSQL", "SELECT * FROM users LIMIT 10", 0, 50).allowed());
  assertFalse(StrictReadOnlySql.analyze("ORACLE", "SELECT id FROM users LIMIT 10", 0, 50).allowed());
 }
 @Test void enforcesBoundParameters() {
  assertFalse(StrictReadOnlySql.analyze("MYSQL", "SELECT id FROM users WHERE id=? LIMIT 10", 0, 50).allowed());
  assertFalse(StrictReadOnlySql.analyze("MYSQL", "SELECT id FROM users LIMIT 999", 0, 50).allowed());
 }
}