package io.github.mcpdataguard.api;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ManagementValidatorsTest {
 @Test void validConfiguration(){
  assertEquals("agent_dev",ManagementValidators.code("agent_dev"));
  assertEquals("MySQL",ManagementValidators.type("MYSQL").equals("MYSQL")?"MySQL":"wrong");
  assertEquals("DEV",ManagementValidators.environment("DEV"));
  assertEquals("db.internal",ManagementValidators.host("db.internal"));
 }
 @Test void blocksInvalidFields(){
  assertThrows(RuntimeException.class,()->ManagementValidators.code("Bad;DROP"));
  assertThrows(RuntimeException.class,()->ManagementValidators.name("\u0000"));
  assertThrows(RuntimeException.class,()->ManagementValidators.host("db.local/other"));
  assertThrows(RuntimeException.class,()->ManagementValidators.environment("UNKNOWN"));
  assertThrows(RuntimeException.class,()->ManagementValidators.type("ORACLE"));
  assertThrows(RuntimeException.class,()->ManagementValidators.version(-1L));
  assertThrows(RuntimeException.class,()->ManagementValidators.bounded(1001,1,1000,"max"));
 }
}
