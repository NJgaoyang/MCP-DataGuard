package io.github.mcpdataguard.standalone;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class MysqlReadGrantPolicyTest {
 @Test void acceptsMinimalReadPrivileges(){
  assertTrue(MysqlReadGrantPolicy.onlySelectOrUsage(List.of(
   "GRANT USAGE ON *.* TO `reader`@`%`",
   "GRANT SELECT ON `dataguard`.`approved_table` TO `reader`@`%`")));
 }
 @Test void rejectsAllAndWriteAndGrants(){
  assertFalse(MysqlReadGrantPolicy.onlySelectOrUsage(List.of(
    "GRANT ALL PRIVILEGES ON `dataguard`.* TO `reader`@`%`")));
  assertFalse(MysqlReadGrantPolicy.onlySelectOrUsage(List.of(
    "GRANT SELECT, UPDATE ON `dataguard`.* TO `reader`@`%`")));
  assertFalse(MysqlReadGrantPolicy.onlySelectOrUsage(List.of(
    "GRANT SELECT ON *.* TO `reader`@`%`")));
  assertFalse(MysqlReadGrantPolicy.onlySelectOrUsage(List.of(
    "GRANT SELECT ON `dataguard`.* TO `reader`@`%` WITH GRANT OPTION")));
  assertFalse(MysqlReadGrantPolicy.onlySelectOrUsage(List.of(
    "GRANT USAGE ON *.* TO `reader`@`%`")));
 }
}
