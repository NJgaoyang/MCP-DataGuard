package io.github.mcpdataguard.mcp;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnGrantPolicyTest {
 private static ColumnGrantPolicy.Grant grant(String table,String column,String decision){
  return new ColumnGrantPolicy.Grant("*",table,column,decision,Set.of("SELECT"));
 }
 @Test void requiresExactColumnsAndPredicateAuthorization(){
  var grants=List.of(grant("accounts","id","ALLOW"),grant("accounts","name","ALLOW"));
  assertTrue(ColumnGrantPolicy.allows("accounts",Set.of("id","name"),grants));
  assertFalse(ColumnGrantPolicy.allows("accounts",Set.of("id","name","ssn"),grants));
  assertFalse(ColumnGrantPolicy.allows("accounts",Set.of("id","email"),grants));
 }
 @Test void explicitDenyWins(){
  var grants=List.of(grant("accounts","id","ALLOW"),grant("*","id","DENY"));
  assertFalse(ColumnGrantPolicy.allows("accounts",Set.of("id"),grants));
 }
 @Test void wildcardCannotGrantUnknownColumns(){
  assertFalse(ColumnGrantPolicy.allows("accounts",Set.of("secret"),
    List.of(grant("accounts","*","ALLOW"))));
 }
 @Test void noGrantAndEmptyProjectionFailClosed(){
  assertFalse(ColumnGrantPolicy.allows("accounts",Set.of("id"),List.of()));
  assertFalse(ColumnGrantPolicy.allows("accounts",Set.of(),List.of(grant("accounts","id","ALLOW"))));
 }
}
