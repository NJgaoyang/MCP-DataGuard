package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.NativeTokens;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Shared usernames in two tenants must never share authentication or sessions. */
class NativeMultiTenantLoginIntegrationTest {
 @Test void sameUsernameRequiresOrganizationAndNeverCrossesTenantBoundary(){
  String url=System.getenv("DG_OUTBOX_E2E_URL");
  Assumptions.assumeTrue(url!=null &&
    url.startsWith("jdbc:postgresql://127.0.0.1:5432/dataguard"));
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(
    url+"?currentSchema=platform","postgres","test-only"));
  UUID orgA=UUID.randomUUID(),orgB=UUID.randomUUID();
  UUID userA=UUID.randomUUID(),userB=UUID.randomUUID();
  String codeA="login_"+orgA.toString().replace("-","");
  String codeB="login_"+orgB.toString().replace("-","");
  String username="sharedlogin";
  String passwordA="SyntheticA-MultiTenant!2026";
  String passwordB="SyntheticB-MultiTenant!2026";
  db.update("INSERT INTO dg_organization(id,code,name) VALUES(?,?,?)",
     orgA,codeA,"First isolated tenant");
  db.update("INSERT INTO dg_organization(id,code,name) VALUES(?,?,?)",
     orgB,codeB,"Second isolated tenant");
  db.update("""
    INSERT INTO dg_user(id,org_id,username,display_name,password_hash)
    VALUES(?,?,?,?,?)
    """,userA,orgA,username,"First user",NativeTokens.passwords().encode(passwordA));
  db.update("""
    INSERT INTO dg_user(id,org_id,username,display_name,password_hash)
    VALUES(?,?,?,?,?)
    """,userB,orgB,username,"Second user",NativeTokens.passwords().encode(passwordB));
  NativeAuthController auth=new NativeAuthController(db);
  var a=auth.login(new NativeAuthController.Login(codeA,username,passwordA));
  assertFalse(a.token().isBlank());
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_native_session WHERE org_id=? AND user_id=? AND revoked_at IS NULL
    """,Long.class,orgA,userA));
  var b=auth.login(new NativeAuthController.Login(codeB,username,passwordB));
  assertFalse(b.token().isBlank());
  assertNotEquals(a.token(),b.token());
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_native_session WHERE org_id=? AND user_id=? AND revoked_at IS NULL
    """,Long.class,orgB,userB));
  var ex=assertThrows(ResponseStatusException.class,()->
    auth.login(new NativeAuthController.Login(codeB,username,passwordA)));
  assertEquals(HttpStatus.UNAUTHORIZED,ex.getStatusCode());
  assertEquals(1,db.queryForObject("""
    SELECT failed_login_attempts FROM dg_user WHERE org_id=? AND id=?
    """,Integer.class,orgB,userB));
  assertEquals(0,db.queryForObject("""
    SELECT failed_login_attempts FROM dg_user WHERE org_id=? AND id=?
    """,Integer.class,orgA,userA));
  // Legacy organization-free form is allowed ONLY when exactly one org exists.
  ex=assertThrows(ResponseStatusException.class,()->
    auth.login(new NativeAuthController.Login("",username,passwordA)));
  assertEquals(HttpStatus.UNAUTHORIZED,ex.getStatusCode());
  ex=assertThrows(ResponseStatusException.class,()->
    auth.login(new NativeAuthController.Login("fake_nonexisting_org",username,passwordA)));
  assertEquals(HttpStatus.UNAUTHORIZED,ex.getStatusCode());
  assertEquals(2L,db.queryForObject("""
    SELECT count(*) FROM dg_native_session WHERE org_id IN (?,?)
    """,Long.class,orgA,orgB));
 }
}
