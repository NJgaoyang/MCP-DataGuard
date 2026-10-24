package io.github.mcpdataguard.standalone;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against an isolated, disposable StarRocks Docker FE/BE. */
class StarRocksLiveJdbcTest {
 @Test
 @EnabledIfEnvironmentVariable(named="DATAGUARD_LIVE_STARROCKS",matches="true")
 void realStarRocksReadAndPhysicalPrivilegeBoundaries() throws Exception {
  String user=System.getenv("DATAGUARD_LIVE_STARROCKS_USER");
  String password=System.getenv("DATAGUARD_LIVE_STARROCKS_PASSWORD");
  assertNotNull(user);
  assertNotNull(password);
  String url=ReadDatasourceDialect.STARROCKS.url("127.0.0.1",9030,"dg_lab",true,false);
  try(var connection=DriverManager.getConnection(url,user,password)) {
   ReadDatasourceDialect.STARROCKS.enforceReadOnly(connection,"dg_lab","lab_orders");
   var read=ReadProjection.build("lab_orders",java.util.Set.of("order_id","amount"),
     5,"order_id","1",null,null);
   try(var ps=connection.prepareStatement(read.sql())){
    ps.setString(1,read.predicateValue());
    try(var rs=ps.executeQuery()){
     assertTrue(rs.next());
     assertEquals(1,rs.getInt("order_id"));
     assertEquals(17,rs.getInt("amount"));
     assertFalse(rs.next());
    }
   }
   assertThrows(SQLException.class,()->{
    try(var stmt=connection.createStatement()) {
     stmt.executeUpdate("INSERT INTO dg_lab.lab_orders(order_id,amount) VALUES(1234,5678)");
    }
   },"A StarRocks read-only principal must not be capable of writing");
  }
 }
}
