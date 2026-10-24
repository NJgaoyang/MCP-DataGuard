package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import io.github.mcpdataguard.sql.StrictReadOnlySql;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Opt-in MySQL 8.4 test, never opens platform production credentials.
 * Verifies the real connection-test controller's TLS identity verification,
 * the restrictive MySQL physical role, and SQL parser rejection.
 * MySQL execution through MCP is deliberately NOT certified by this test.
 */
class MySqlDatasourceTlsIntegrationTest {
 private static void fixture(){
  Assumptions.assumeTrue("true".equals(System.getenv("DG_MYSQL_TEST_ENABLED")));
  Assumptions.assumeTrue("dg_probe_ro".equals(System.getenv("DG_TARGET_MYSQL_E2E_USER")));
  Assumptions.assumeTrue(System.getenv("DG_TARGET_MYSQL_E2E_PASSWORD")!=null);
 }
 private static String url(){
  return "jdbc:mysql://127.0.0.1:3307/dg_mysql_test"
   +"?sslMode=VERIFY_IDENTITY&connectTimeout=3000&socketTimeout=5000";
 }
 @Test void physicallyReadonlyMySqlRoleUsesVerifiedTls() throws Exception {
  fixture();
  try(Connection c=DriverManager.getConnection(url(),
        System.getenv("DG_TARGET_MYSQL_E2E_USER"),
        System.getenv("DG_TARGET_MYSQL_E2E_PASSWORD"))){
   assertEquals("MySQL",c.getMetaData().getDatabaseProductName());
   try(Statement s=c.createStatement()){
    try(ResultSet rs=s.executeQuery("SHOW STATUS LIKE 'Ssl_cipher'")){
     assertTrue(rs.next());
     assertFalse(rs.getString(2).isBlank(),"TLS must actually be negotiated");
    }
    try(ResultSet rs=s.executeQuery("SELECT id,amount FROM readonly_probe ORDER BY id")){
     assertTrue(rs.next());
     assertEquals(1,rs.getInt("id"));
     assertEquals(123,rs.getInt("amount"));
     assertTrue(rs.next());
     assertEquals(2,rs.getInt("id"));
     assertEquals(456,rs.getInt("amount"));
     assertFalse(rs.next());
    }
    assertThrows(SQLException.class,()->
       s.executeUpdate("UPDATE readonly_probe SET amount=999 WHERE id=1"),
       "Physical MySQL SELECT-only user cannot update records");
   }
  }
 }
 @Test void managementConnectionControllerVerifiesIdentityAndDoesNotAuthorizeExecution() throws Exception {
  fixture();
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  UserPrincipalResolver users=mock(UserPrincipalResolver.class);
  Jwt jwt=mock(Jwt.class);
  UserPrincipal user=new UserPrincipal(UUID.randomUUID(),UUID.randomUUID(),
    "tester","Test Operator","native","subject");
  when(users.requireUser(jwt)).thenReturn(user);
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(inv->{
   RowMapper<?> mapper=inv.getArgument(1);
   ResultSet rs=mock(ResultSet.class);
   when(rs.getString(1)).thenReturn("127.0.0.1");
   when(rs.getInt(2)).thenReturn(3307);
   when(rs.getString(3)).thenReturn("dg_mysql_test");
   when(rs.getString(4)).thenReturn("MYSQL");
   when(rs.getString(5)).thenReturn("ENV");
   when(rs.getString(6)).thenReturn("DG_TARGET_MYSQL_E2E");
   return List.of(mapper.mapRow(rs,0));
  });
  var controller=new DatasourceConnectionController(jdbc,users,"127.0.0.1");
  Map<String,Object> result=controller.test(jwt,UUID.randomUUID());
  assertEquals(true,result.get("connected"));
  assertEquals("VERIFY_IDENTITY",result.get("tls"));
  assertEquals(false,result.get("sqlExecutionEnabled"));
  verify(users).requirePermission(user,"datasource:test");
  verify(jdbc).update(contains("INSERT INTO dg_system_event"),
    any(UUID.class),eq(user.orgId()),eq(user.userId().toString()),
    eq("DATASOURCE_CONNECTION_TEST"),anyString());
  var other=new DatasourceConnectionController(jdbc,users,"production-db.example.org");
  ResponseStatusException ex=assertThrows(ResponseStatusException.class,
    ()->other.test(jwt,UUID.randomUUID()));
  assertEquals(403,ex.getStatusCode().value(),"MySQL host must be in operator allowlist");
 }
 @Test void mysqlSqlParserRejectsWritesAndMultiStatementInjection(){
  fixture();
  assertEquals("SELECT",StrictReadOnlySql.analyze(
    "MYSQL","SELECT id FROM readonly_probe LIMIT 2",0,10).operation());
  assertFalse(StrictReadOnlySql.analyze("MYSQL",
    "UPDATE readonly_probe SET amount=999 WHERE id=1",0,10).allowed());
  assertFalse(StrictReadOnlySql.analyze("MYSQL",
    "SELECT id FROM readonly_probe LIMIT 2; DROP TABLE readonly_probe",0,10).allowed());
 }
}
