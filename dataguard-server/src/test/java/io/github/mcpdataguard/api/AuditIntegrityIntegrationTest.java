package io.github.mcpdataguard.api;

import io.github.mcpdataguard.mcp.DeniedSqlAuditChain;
import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in PostgreSQL test proving that modified audit evidence fails verification. */
class AuditIntegrityIntegrationTest {
 @Test void detectsTamperedAuditEventAndHonorsTenantScope(){
  String url=System.getenv("DG_OUTBOX_E2E_URL");
  Assumptions.assumeTrue(url!=null && url.startsWith("jdbc:postgresql://127.0.0.1:5432/"));
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(\n    url+"?currentSchema=platform","postgres","test-only"));
  UUID org=UUID.randomUUID(),user=UUID.randomUUID(),agent=UUID.randomUUID(),
    source=UUID.randomUUID(),request=UUID.randomUUID();
  db.update("INSERT INTO platform.dg_organization(id,code,name) VALUES(?,?,?)",
    org,"audit_"+org.toString().replace("-",""),"Audit E2E");
  db.update("""
    INSERT INTO platform.dg_user(id,org_id,username,display_name) VALUES(?,?,?,?)
    """,user,org,"tester_"+user.toString().replace("-",""),"Audit user");
  db.update("""
    INSERT INTO platform.dg_agent(id,org_id,owner_user_id,code,name) VALUES(?,?,?,?,?)
    """,agent,org,user,"audit_agent","Audit Agent");
  db.update("""
    INSERT INTO platform.dg_datasource
      (id,org_id,owner_user_id,code,name,db_type,environment,host,port,database_name,status)
    VALUES(?,?,?,?,?,'POSTGRESQL','TEST','localhost',5432,'dataguard','ACTIVE')
    """,source,org,user,"audit_db","Audit datasource");
  db.update("""
    INSERT INTO platform.dg_sql_request
      (id,org_id,agent_id,datasource_id,tool_name,decision,request_status)
    VALUES(?,?,?,?,'check_sql','DENY','DENIED')
    """,request,org,agent,source);
  DeniedSqlAuditChain chain=new DeniedSqlAuditChain(db);
  chain.append(org,agent,request,"a".repeat(64),"UNSAFE_SQL");
  UserPrincipalResolver users=mock(UserPrincipalResolver.class);
  Jwt jwt=mock(Jwt.class);
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(org,user,
    "auditadmin","Audit Admin","issuer","subject"));
  AuditIntegrityController controller=new AuditIntegrityController(db,users);
  assertEquals(true,controller.verify(jwt,"mcp-denied-sql").get("verified"));
  UUID stream=db.queryForObject("""
    SELECT id FROM dg_audit_stream WHERE org_id=? AND stream_key='mcp-denied-sql'
    """,UUID.class,org);
  db.update("""
    UPDATE dg_audit_event SET event_data=
      jsonb_set(event_data,'{reason}','"TAMPERED"'::jsonb)
    WHERE org_id=? AND stream_id=?
    """,org,stream);
  var damaged=controller.verify(jwt,"mcp-denied-sql");
  assertEquals(false,damaged.get("verified"));
  assertEquals("HASH_MISMATCH",damaged.get("code"));
  when(users.requireUser(jwt)).thenReturn(new UserPrincipal(
    UUID.randomUUID(),user,"auditadmin","Audit Admin","issuer","subject"));
  assertEquals("STREAM_NOT_FOUND",controller.verify(jwt,"mcp-denied-sql").get("code"));
 }
}
