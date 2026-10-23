package io.github.mcpdataguard.mcp;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test against disposable PostgreSQL 17 with a verified localhost TLS cert.
 * Invoked only with DG_WRITE_JDBC_E2E=true in an isolated container network.
 */
class CertifiedWriteJdbcTlsTest {
 private static void requireFixture(){
  Assumptions.assumeTrue("true".equals(System.getenv("DG_WRITE_JDBC_E2E")));
 }
 private static CertifiedWriteJdbcExecutor.Target user(String login){
  return new CertifiedWriteJdbcExecutor.Target(
      "localhost",5432,"dataguard",login,"test-only");
 }
 @Test void leastPrivilegeRoleCanPerformSingleRowPreparedUpdate()throws Exception{
  requireFixture();
  var executor=new CertifiedWriteJdbcExecutor("localhost");
  var result=executor.execute(user("dg_test_write_role"),
    "UPDATE write_probe SET amount=? WHERE id=?",List.of(750,1));
  assertEquals(1,result.affectedRows());
  assertEquals("UPDATE",result.operation());
 }
 @Test void zeroAffectedRowsTriggersRollback()throws Exception{
  requireFixture();
  var executor=new CertifiedWriteJdbcExecutor("localhost");
  assertThrows(SecurityException.class,()->executor.execute(
    user("dg_test_write_role"),
    "UPDATE write_probe SET amount=? WHERE id=?",List.of(950,9999)));
 }
 @Test void elevatedIdentityIsNeverAllowedToWrite()throws Exception{
  requireFixture();
  var executor=new CertifiedWriteJdbcExecutor("localhost");
  assertThrows(SecurityException.class,()->executor.execute(user("postgres"),
    "UPDATE write_probe SET amount=? WHERE id=?",List.of(950,1)));
 }
 @Test void certificateHostnameMismatchRefusesConnection()throws Exception{
  requireFixture();
  var executor=new CertifiedWriteJdbcExecutor("localhost,127.0.0.1");
  var target=new CertifiedWriteJdbcExecutor.Target(
    "127.0.0.1",5432,"dataguard","dg_test_write_role","test-only");
  assertThrows(java.sql.SQLException.class,()->executor.execute(target,
    "UPDATE write_probe SET amount=? WHERE id=?",List.of(950,1)));
 }
}
