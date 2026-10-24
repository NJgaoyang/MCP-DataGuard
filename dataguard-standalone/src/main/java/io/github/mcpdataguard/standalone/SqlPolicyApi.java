package io.github.mcpdataguard.standalone;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sql-policies")
public class SqlPolicyApi {
 private final JdbcTemplate jdbc;
 private final AuditService audit;
 public SqlPolicyApi(JdbcTemplate jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}

 @GetMapping
 public List<Map<String,Object>> list(Authentication auth){
  Guard.requireAdmin(auth);
  return jdbc.queryForList("SELECT p.id,p.code,p.name,p.status,p.latest_version AS latestVersion,p.version,(SELECT v.decision FROM sql_policy_versions v WHERE v.policy_id=p.id AND v.revision=p.latest_version) AS latestDecision FROM sql_policies p ORDER BY p.created_at DESC LIMIT 200");
 }
 public record NewPolicy(String code,String name){}
 @PostMapping
 @Transactional
 public Map<String,Object> create(Authentication auth,@RequestBody NewPolicy input){
  Guard.requireAdmin(auth);
  if(input==null||input.code()==null||!input.code().matches("[a-z][a-z0-9_-]{2,119}")||input.name()==null||input.name().isBlank()||input.name().length()>200)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid policy");
  String id=UUID.randomUUID().toString();
  String actor=userId(auth);
  try{jdbc.update("INSERT INTO sql_policies(id,code,name,created_by) VALUES(?,?,?,?)",id,input.code(),input.name().strip(),actor);}
  catch(org.springframework.dao.DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"策略编码已存在");}
  audit.log(auth.getName(),"SQL_POLICY_CREATED","SQL_POLICY",id,"SUCCESS","draft");
  return Map.of("id",id,"status","DRAFT","version",0);
 }

 public record RuleInput(String decision,int priority,String table,String environment,String operation){}
 private static void validateRule(RuleInput input){
  if(input==null||!List.of("DENY","REQUIRE_APPROVAL").contains(input.decision())||input.priority()<1||input.priority()>10000||
    input.table()==null||!(input.table().equals("*")||input.table().matches("[a-z][a-z0-9_]{0,254}"))||
    !List.of("DEV","TEST","STAGING","PROD").contains(input.environment())||
    !List.of("SELECT","UPDATE","DELETE","INSERT").contains(input.operation()))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"规则内容无效");
 }
 @GetMapping("/{id}/versions")
 public List<Map<String,Object>> versions(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  requirePolicy(id);
  return jdbc.queryForList("SELECT revision,priority,decision,table_name AS tableName,environment,operation,published_at IS NOT NULL AS published FROM sql_policy_versions WHERE policy_id=? ORDER BY revision DESC LIMIT 100",id.toString());
 }
 @PostMapping("/{id}/versions")
 @Transactional
 public Map<String,Object> addVersion(Authentication auth,@PathVariable UUID id,@RequestBody RuleInput input){
  Guard.requireAdmin(auth);validateRule(input);
  List<Integer> latest=jdbc.query("SELECT latest_version FROM sql_policies WHERE id=? AND status='DRAFT' FOR UPDATE",(r,n)->r.getInt(1),id.toString());
  if(latest.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"策略草稿不可用");
  int revision=latest.get(0)+1;String actor=userId(auth);
  jdbc.update("INSERT INTO sql_policy_versions(id,policy_id,revision,priority,decision,table_name,environment,operation,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
    UUID.randomUUID().toString(),id.toString(),revision,input.priority(),input.decision(),input.table(),input.environment(),input.operation(),actor);
  jdbc.update("UPDATE sql_policies SET latest_version=?,version=version+1 WHERE id=?",revision,id.toString());
  audit.log(auth.getName(),"SQL_POLICY_VERSION_CREATED","SQL_POLICY",id.toString(),"SUCCESS","revision="+revision);
  return Map.of("revision",revision,"status","DRAFT","executionEnabled",false);
 }
 public record Simulation(String table,String environment,String operation){}
 @PostMapping("/{id}/simulate")
 public Map<String,Object> simulate(Authentication auth,@PathVariable UUID id,@RequestBody Simulation in){
  Guard.requireAdmin(auth);
  if(in==null||in.table()==null||!(in.table().equals("*")||in.table().matches("[a-z][a-z0-9_]{0,254}"))||!List.of("DEV","TEST","STAGING","PROD").contains(in.environment())||!List.of("SELECT","UPDATE","DELETE","INSERT").contains(in.operation()))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"模拟条件无效");
  requirePolicy(id);
  List<Map<String,Object>> rows=jdbc.queryForList("SELECT revision,priority,decision FROM sql_policy_versions WHERE policy_id=? AND revision=(SELECT latest_version FROM sql_policies WHERE id=?) AND table_name IN (?, '*') AND environment=? AND operation=? ORDER BY priority, CASE decision WHEN 'DENY' THEN 0 ELSE 1 END LIMIT 1",id.toString(),id.toString(),in.table(),in.environment(),in.operation());
  if(rows.isEmpty())return Map.of("matched",false,"decision","DENY","simulationOnly",true,"executionEnabled",false);
  Map<String,Object> row=rows.get(0);return Map.of("matched",true,"decision",row.get("decision"),"revision",row.get("revision"),"priority",row.get("priority"),"simulationOnly",true,"executionEnabled",false);
 }
 public record Lifecycle(long expectedVersion){}
 @PostMapping("/{id}/publish")
 @Transactional
 public Map<String,Object> publish(Authentication auth,@PathVariable UUID id,@RequestBody Lifecycle in){
  Guard.requireAdmin(auth);
  List<Map<String,Object>> rows=jdbc.queryForList("SELECT latest_version,version FROM sql_policies WHERE id=? AND status='DRAFT' FOR UPDATE",id.toString());
  if(rows.size()!=1||in==null||((Number)rows.get(0).get("version")).longValue()!=in.expectedVersion())throw new ResponseStatusException(HttpStatus.CONFLICT,"策略版本已变化");
  int revision=((Number)rows.get(0).get("latest_version")).intValue();
  if(revision<1)throw new ResponseStatusException(HttpStatus.CONFLICT,"请先添加规则");
  String actor=userId(auth);
  int marked=jdbc.update("UPDATE sql_policy_versions SET published_by=?,published_at=NOW(3) WHERE policy_id=? AND revision=? AND decision='DENY' AND published_at IS NULL",actor,id.toString(),revision);
  if(marked!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"仅可发布拒绝规则");
  int changed=jdbc.update("UPDATE sql_policies SET status='ACTIVE',version=version+1 WHERE id=? AND status='DRAFT' AND version=?",id.toString(),in.expectedVersion());
  if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"策略版本已变化");
  audit.log(auth.getName(),"SQL_POLICY_PUBLISHED","SQL_POLICY",id.toString(),"SUCCESS","deny-only; execution disabled");
  return Map.of("status","ACTIVE","revision",revision,"executionEnabled",false);
 }
 @PostMapping("/{id}/disable")
 @Transactional
 public Map<String,Object> disable(Authentication auth,@PathVariable UUID id,@RequestBody Lifecycle in){
  Guard.requireAdmin(auth);
  if(in==null||jdbc.update("UPDATE sql_policies SET status='DISABLED',version=version+1 WHERE id=? AND status='ACTIVE' AND version=?",id.toString(),in.expectedVersion())!=1)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"策略状态已变化");
  audit.log(auth.getName(),"SQL_POLICY_DISABLED","SQL_POLICY",id.toString(),"SUCCESS","");
  return Map.of("status","DISABLED","executionEnabled",false);
 }
 @PostMapping("/effective-decision")
 public Map<String,Object> effective(Authentication auth,@RequestBody Simulation in){
  Guard.requireAdmin(auth);
  if(in==null||in.table()==null||!(in.table().equals("*")||in.table().matches("[a-z][a-z0-9_]{0,254}"))||!List.of("DEV","TEST","STAGING","PROD").contains(in.environment())||!List.of("SELECT","UPDATE","DELETE","INSERT").contains(in.operation()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"评估条件无效");
  List<Map<String,Object>> rows=jdbc.queryForList("SELECT p.code,v.revision,v.priority,v.decision FROM sql_policy_versions v JOIN sql_policies p ON p.id=v.policy_id WHERE p.status='ACTIVE' AND v.published_at IS NOT NULL AND v.table_name IN (?, '*') AND v.environment=? AND v.operation=? ORDER BY v.priority,CASE v.decision WHEN 'DENY' THEN 0 ELSE 1 END,p.id LIMIT 100",in.table(),in.environment(),in.operation());
  if(rows.isEmpty())return Map.of("decision","DENY","matched",false,"matchedRules",List.of(),"simulationOnly",true,"executionEnabled",false);
  Map<String,Object> first=rows.get(0);
  return Map.of("decision",first.get("decision"),"matched",true,"matchedRules",rows,"simulationOnly",true,"executionEnabled",false);
 }
 private void requirePolicy(UUID id){if(jdbc.queryForList("SELECT id FROM sql_policies WHERE id=?",id.toString()).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"策略不存在");}
 private String userId(Authentication auth){return jdbc.queryForObject("SELECT id FROM app_users WHERE username=?",String.class,auth.getName());}
}
