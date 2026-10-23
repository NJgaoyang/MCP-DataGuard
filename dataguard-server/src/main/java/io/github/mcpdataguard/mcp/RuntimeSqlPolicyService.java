package io.github.mcpdataguard.mcp;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Single runtime policy evaluator for real MCP requests.
 * Published DENY wins over every lower-priority or approval rule.
 * NO_RULE is NOT an execution authorization; Agent ACL, object grants,
 * physical capability and the execution gate remain mandatory.
 */
@Service
public class RuntimeSqlPolicyService {
 private final JdbcTemplate jdbc;
 public RuntimeSqlPolicyService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Matched(UUID policyId,String code,int priority,String decision){}
 public record Verdict(String decision,List<Matched> matches){
  public boolean denied(){return "DENY".equals(decision);}
  public boolean approvalRequired(){return "REQUIRE_APPROVAL".equals(decision);}
 }
 public Verdict evaluate(UUID org,String environment,String table,String operation){
  if(org==null||environment==null||table==null||operation==null||
     !List.of("DEV","TEST","STAGING","PROD").contains(environment)||
     !table.matches("[a-z][a-z0-9_]{0,254}")||
     !List.of("SELECT","UPDATE","DELETE","INSERT").contains(operation))
   throw new SecurityException("INVALID_SQL_POLICY_SCOPE");
  List<Matched> matches=jdbc.query("""
    SELECT p.id,p.code,v.priority,v.decision
    FROM dg_sql_policy p JOIN dg_sql_policy_version v
      ON v.org_id=p.org_id AND v.policy_id=p.id
      AND v.revision=p.latest_version
    WHERE p.org_id=? AND p.status='ACTIVE' AND v.published_at IS NOT NULL
      AND v.rule->>'table'=? AND v.rule->>'environment'=?
      AND v.rule->>'operation'=? AND v.decision IN ('DENY','REQUIRE_APPROVAL')
    ORDER BY v.priority,
      CASE WHEN v.decision='DENY' THEN 0 ELSE 1 END,p.id
    LIMIT 100
    """,(rs,i)->new Matched(rs.getObject(1,UUID.class),rs.getString(2),
      rs.getInt(3),rs.getString(4)),org,table,environment,operation);
  if(matches==null)throw new SecurityException("SQL_POLICY_DATABASE_UNAVAILABLE");
  if(matches.stream().anyMatch(row->"DENY".equals(row.decision())))
   return new Verdict("DENY",List.copyOf(matches));
  if(matches.stream().anyMatch(row->"REQUIRE_APPROVAL".equals(row.decision())))
   return new Verdict("REQUIRE_APPROVAL",List.copyOf(matches));
  return new Verdict("NO_RULE",List.of());
 }
}
