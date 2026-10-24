package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Administrative alert triage, scoped to tenant. No sensitive alert payloads returned. */
@RestController
@RequestMapping("/api/v1/security-alerts")
public class SecurityAlertController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public SecurityAlertController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 private UserPrincipal actor(Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);
  users.requirePermission(u,"identity:admin");
  return u;
 }
 @GetMapping
 public List<Map<String,String>> list(@AuthenticationPrincipal Jwt jwt,
     @RequestParam(defaultValue="") String status,
     @RequestParam(defaultValue="") String severity,
     @RequestParam(defaultValue="") String rule,
     @RequestParam(defaultValue="50") int limit,
     @RequestParam(defaultValue="0") int offset){
  UserPrincipal u=actor(jwt);
  if(!List.of("","OPEN","ACKNOWLEDGED","RESOLVED").contains(status) ||
     !List.of("","LOW","MEDIUM","HIGH","CRITICAL").contains(severity) ||
     !rule.matches("[A-Z0-9_]{0,100}") ||
     limit<1||limit>100||offset<0||offset>100000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid security-alert filter");
  return jdbc.query("""
    SELECT a.id,a.fingerprint,a.event_state,a.observed_at,r.code,r.severity
    FROM dg_alert_event a JOIN dg_alert_rule r ON r.org_id=a.org_id AND r.id=a.rule_id
    WHERE a.org_id=? AND (?='' OR a.event_state=?)
      AND (?='' OR r.severity=?) AND (?='' OR r.code=?)
    ORDER BY a.observed_at DESC,a.id DESC LIMIT ? OFFSET ?
    """,(rs,i)->Map.of("id",rs.getString("id"),"fingerprint",rs.getString("fingerprint"),
      "status",rs.getString("event_state"),"rule",rs.getString("code"),
      "severity",rs.getString("severity"),
      "observedAt",rs.getTimestamp("observed_at").toInstant().toString()),
      u.orgId(),status,status,severity,severity,rule,rule,limit,offset);
 }
 @PostMapping("/{alertId}/acknowledge")
 @Transactional
 public Map<String,String> acknowledge(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID alertId){
  UserPrincipal u=actor(jwt);
  int count=jdbc.update("""
    UPDATE dg_alert_event SET event_state='ACKNOWLEDGED'
    WHERE org_id=? AND id=? AND event_state='OPEN'
    """,u.orgId(),alertId);
  if(count!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Alert not open or unavailable");
  jdbc.update("""
    INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
    VALUES (?,?,?,'SECURITY_ALERT_ACKNOWLEDGED','ALERT',?)
    """,UUID.randomUUID(),u.orgId(),u.userId().toString(),alertId.toString());
  return Map.of("status","ACKNOWLEDGED");
 }
 /** Explicit resolution keeps the ACKNOWLEDGED state as immutable history
  * in dg_system_event; only acknowledged incidents can be resolved.
  */
 @PostMapping("/{alertId}/resolve")
 @Transactional
 public Map<String,String> resolve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID alertId){
  UserPrincipal u=actor(jwt);
  int updated=jdbc.update("""
    UPDATE dg_alert_event SET event_state='RESOLVED',resolved_at=now()
    WHERE org_id=? AND id=? AND event_state='ACKNOWLEDGED'
    """,u.orgId(),alertId);
  if(updated!=1)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Alert not acknowledged or unavailable");
  jdbc.update("""
    INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
    VALUES (?,?,?,'SECURITY_ALERT_RESOLVED','ALERT',?)
    """,UUID.randomUUID(),u.orgId(),u.userId().toString(),alertId.toString());
  return Map.of("status","RESOLVED");
 }
}
