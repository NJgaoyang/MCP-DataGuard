package io.github.mcpdataguard.mcp;

import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Genuine PostgreSQL regression test for same-tenant concurrency-safe incident
 * de-duplication, cross-tenant separation and re-opening acknowledged incidents.
 * Runs only in the disposable PostgreSQL network namespace used by release gate.
 */
class DeniedSqlAlertIntegrationTest {
 private static JdbcTemplate isolatedDatabase(){
  String url=System.getenv("DG_OUTBOX_E2E_URL");
  Assumptions.assumeTrue(url!=null &&
    url.startsWith("jdbc:postgresql://127.0.0.1:5432/dataguard"));
  return new JdbcTemplate(new DriverManagerDataSource(
    url+"?currentSchema=platform","postgres","test-only"));
 }
 private static UUID organization(JdbcTemplate db){
  UUID id=UUID.randomUUID();
  db.update("INSERT INTO dg_organization(id,code,name) VALUES(?,?,?)",
    id,"alert_"+id.toString().replace("-",""),"Alert isolation fixture");
  return id;
 }
 @Test void oneOpenIncidentPerTenantSourceAndReason(){
  JdbcTemplate db=isolatedDatabase();
  DeniedSqlAlertService events=new DeniedSqlAlertService(db);
  UUID orgA=organization(db),orgB=organization(db),source=UUID.randomUUID();
  events.record(orgA,source,"SQL_POLICY_HARD_DENY");
  events.record(orgA,source,"SQL_POLICY_HARD_DENY");
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_alert_event
    WHERE org_id=? AND event_state='OPEN'
    """,Long.class,orgA));
  // Different reason and different organization produce independent incidents.
  events.record(orgA,source,"COLUMN_NOT_AUTHORIZED");
  events.record(orgB,source,"SQL_POLICY_HARD_DENY");
  assertEquals(2L,db.queryForObject("""
    SELECT count(*) FROM dg_alert_event WHERE org_id=? AND event_state='OPEN'
    """,Long.class,orgA));
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_alert_event WHERE org_id=? AND event_state='OPEN'
    """,Long.class,orgB));
  assertEquals(0L,db.queryForObject("""
    SELECT count(*) FROM dg_alert_event
    WHERE org_id=? AND details::text LIKE '%SELECT %'
    """,Long.class,orgA));
  db.update("""
    UPDATE dg_alert_event SET event_state='ACKNOWLEDGED'
    WHERE org_id=? AND details->>'reason'='SQL_POLICY_HARD_DENY'
    """,orgA);
  events.record(orgA,source,"SQL_POLICY_HARD_DENY");
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_alert_event
    WHERE org_id=? AND event_state='OPEN'
      AND details->>'reason'='SQL_POLICY_HARD_DENY'
    """,Long.class,orgA));
  assertEquals(1L,db.queryForObject("""
    SELECT count(*) FROM dg_alert_event
    WHERE org_id=? AND event_state='ACKNOWLEDGED'
      AND details->>'reason'='SQL_POLICY_HARD_DENY'
    """,Long.class,orgA));
 }
 @Test void invalidAlertReasonIsNeverWritten(){
  JdbcTemplate db=isolatedDatabase();
  DeniedSqlAlertService events=new DeniedSqlAlertService(db);
  UUID org=organization(db);
  assertThrows(IllegalArgumentException.class,()->
    events.record(org,UUID.randomUUID(),"SQL text: SELECT secret FROM customers"));
  assertEquals(0L,db.queryForObject("""
    SELECT count(*) FROM dg_alert_event WHERE org_id=?
    """,Long.class,org));
 }
}
