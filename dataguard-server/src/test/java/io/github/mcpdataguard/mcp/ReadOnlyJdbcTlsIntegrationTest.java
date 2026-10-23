package io.github.mcpdataguard.mcp;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real PostgreSQL 17 JDBC/TLS tests in an isolated network namespace. */
class ReadOnlyJdbcTlsIntegrationTest {
 private static boolean available(){
  return "true".equals(System.getenv("DG_READ_JDBC_E2E"));
 }
 private static IsolatedReadOnlyExecutor.Target reader(){
  return new IsolatedReadOnlyExecutor.Target("localhost",5432,
    "dataguard","dg_tls_reader","test-only");
 }
 private static IsolatedReadOnlyExecutor.Request request(int rows,int bytes){
  return new IsolatedReadOnlyExecutor.Request(
    "SELECT id,amount FROM readonly_probe LIMIT 2",
    List.of(),Set.of("id","amount"),rows,bytes,5);
 }
 @Test void verifiedTlsAndPhysicalReaderReturnOnlyMaskedValues()throws Exception{
  Assumptions.assumeTrue(available());
  var executor=new IsolatedReadOnlyExecutor("localhost");
  var result=executor.execute(reader(),request(2,512));
  assertEquals(List.of("id","amount"),result.columns());
  assertEquals(2,result.rows().size());
  assertEquals(List.of("[REDACTED]","[REDACTED]"),result.rows().get(0));
 }
 @Test void physicalSuperuserCannotPassReadOnlyIdentityCertification(){
  Assumptions.assumeTrue(available());
  var executor=new IsolatedReadOnlyExecutor("localhost");
  var elevated=new IsolatedReadOnlyExecutor.Target(
    "localhost",5432,"dataguard","postgres","test-only");
  assertThrows(SecurityException.class,()->executor.execute(elevated,request(2,512)));
 }
 @Test void missingTlsHostnameVerificationFails(){
  Assumptions.assumeTrue(available());
  var executor=new IsolatedReadOnlyExecutor("localhost,127.0.0.1");
  var mismatched=new IsolatedReadOnlyExecutor.Target(
    "127.0.0.1",5432,"dataguard","dg_tls_reader","test-only");
  assertThrows(java.sql.SQLException.class,()->
    executor.execute(mismatched,request(2,512)));
 }
 @Test void byteLimitEnforcedOnMaskedResults(){
  Assumptions.assumeTrue(available());
  var executor=new IsolatedReadOnlyExecutor("localhost");
  assertThrows(SecurityException.class,()->executor.execute(reader(),request(2,5)));
 }
 @Test void allowlistedHostIsStillRequired(){
  Assumptions.assumeTrue(available());
  var executor=new IsolatedReadOnlyExecutor("other.example.com");
  assertThrows(SecurityException.class,()->executor.execute(reader(),request(2,512)));
 }
}
