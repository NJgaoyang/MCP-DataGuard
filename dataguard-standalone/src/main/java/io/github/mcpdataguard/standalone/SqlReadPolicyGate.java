package io.github.mcpdataguard.standalone;

import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Production read-policy enforcement, NOT a simulation endpoint.
 * Published policies may only DENY today. Approval is always separately
 * required by ReadApi and must never be bypassed by an ALLOW simulation.
 * Fail closed on unrecognized configurations and policy database errors.
 */
@Service
final class SqlReadPolicyGate {
 private final JdbcTemplate jdbc;
 private final String environment;
 SqlReadPolicyGate(JdbcTemplate jdbc,
   @Value("${dataguard.policy.environment:PROD}") String env) {
  this.jdbc=jdbc;
  String e=env==null?"":env.strip().toUpperCase(Locale.ROOT);
  if(!List.of("DEV","TEST","STAGING","PROD").contains(e))
   throw new IllegalStateException("Invalid dataguard.policy.environment");
  this.environment=e;
 }
 void enforce(String table){
  String validTable=Guard.identifier(table);
  List<String> decisions=jdbc.query("""
    SELECT v.decision FROM sql_policies p
    JOIN sql_policy_versions v ON p.id=v.policy_id
    WHERE p.status='ACTIVE' AND v.published_at IS NOT NULL
      AND v.revision=p.latest_version
      AND v.environment=? AND v.operation='SELECT'
      AND v.table_name IN (?, '*')
    ORDER BY v.priority ASC, p.id ASC
    """,(rs,i)->rs.getString(1),environment,validTable);
  if(decisions.stream().anyMatch(d->!"DENY".equals(d)))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Unrecognized active SQL policy decision");
  if(!decisions.isEmpty())
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Active SQL read DENY policy");
 }
 String environment(){return environment;}
}
