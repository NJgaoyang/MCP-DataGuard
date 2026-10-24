package io.github.mcpdataguard.standalone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MysqlProductionGateTest {
 @Test void acceptsLoopbackAndVerifiedTLS(){
  assertDoesNotThrow(()->MysqlProductionGate.validate(
   "jdbc:mysql://mysql.internal:3306/dataguard?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
    true,"127.0.0.1"));
 }
 @Test void refusesNonVerifiedTransportAndInsecureCookie(){
  for(String mode:new String[]{"DISABLED","REQUIRED","VERIFY_CA"}){
   assertThrows(IllegalStateException.class,()->MysqlProductionGate.validate(
     "jdbc:mysql://mysql.internal:3306/dataguard?sslMode="+mode,true,"127.0.0.1"));
  }
  assertThrows(IllegalStateException.class,()->MysqlProductionGate.validate(
   "jdbc:mysql://mysql.internal/db?sslMode=VERIFY_IDENTITY",false,"127.0.0.1"));
  assertThrows(IllegalStateException.class,()->MysqlProductionGate.validate(
   "jdbc:mysql://mysql.internal/db?sslMode=VERIFY_IDENTITY",true,"0.0.0.0"));
 }
}
