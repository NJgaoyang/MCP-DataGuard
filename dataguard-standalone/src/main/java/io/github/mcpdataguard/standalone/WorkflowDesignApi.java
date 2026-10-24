package io.github.mcpdataguard.standalone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
@RequestMapping("/api/workflows")
public class WorkflowDesignApi {
 private final JdbcTemplate jdbc;
 private final AuditService audit;
 private final ObjectMapper mapper=new ObjectMapper();
 public WorkflowDesignApi(JdbcTemplate jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}
 public record Step(String role,String label){}
 public record DesignInput(String code,String name,List<Step> steps,boolean highRiskOnlyLast){}
 public record PublishInput(int revision,long expectedVersion){}
 @GetMapping
 public List<Map<String,Object>> list(Authentication auth){
  Guard.requireAdmin(auth);
  return jdbc.queryForList("SELECT w.id,w.code,w.name,w.version,COALESCE(MAX(v.revision),0) AS revision,COALESCE(MAX(CASE WHEN v.status='PUBLISHED' THEN v.revision END),0) AS publishedRevision FROM workflow_designs w LEFT JOIN workflow_design_versions v ON v.workflow_id=w.id GROUP BY w.id,w.code,w.name,w.version ORDER BY w.name LIMIT 100");
 }
 @GetMapping("/{id}/versions")
 public List<Map<String,Object>> versions(Authentication auth,@PathVariable UUID id){
  Guard.requireAdmin(auth);
  return jdbc.query("SELECT revision,steps_json,graph_digest,high_risk_only_last,status FROM workflow_design_versions WHERE workflow_id=? ORDER BY revision DESC LIMIT 30",(r,n)->{
   String json=r.getString("steps_json");
   if(!digest(json).equals(r.getString("graph_digest")))throw new ResponseStatusException(HttpStatus.CONFLICT,"流程版本校验失败");
   try{return Map.<String,Object>of("revision",r.getInt("revision"),"steps",mapper.readValue(json,new TypeReference<List<Step>>(){}),"highRiskOnlyLast",r.getBoolean("high_risk_only_last"),"status",r.getString("status"));}
   catch(Exception ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"流程版本内容无效");}
  },id.toString());
 }
 @PostMapping
 @Transactional
 public Map<String,Object> create(Authentication auth,@RequestBody DesignInput input){
  Guard.requireAdmin(auth);validate(input,true);
  String id=UUID.randomUUID().toString(),actor=userId(auth),json=json(input.steps());
  jdbc.update("INSERT INTO workflow_designs(id,code,name,created_by) VALUES(?,?,?,?)",id,input.code(),input.name().strip(),actor);
  insertVersion(id,1,input,json,actor);
  audit.log(auth.getName(),"WORKFLOW_DESIGN_CREATED","WORKFLOW",id,"SUCCESS","revision=1");
  return Map.of("id",id,"revision",1,"status","DRAFT");
 }
 @PostMapping("/{id}/draft")
 @Transactional
 public Map<String,Object> draft(Authentication auth,@PathVariable UUID id,@RequestBody DesignInput input){
  Guard.requireAdmin(auth);validate(input,false);
  List<String> found=jdbc.query("SELECT id FROM workflow_designs WHERE id=? FOR UPDATE",(r,n)->r.getString(1),id.toString());
  if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"流程不存在");
  int latest=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM workflow_design_versions WHERE workflow_id=?",Integer.class,id.toString());
  int rev=latest+1;String actor=userId(auth),json=json(input.steps());
  jdbc.update("UPDATE workflow_designs SET name=?,version=version+1 WHERE id=?",input.name().strip(),id.toString());
  insertVersion(id.toString(),rev,input,json,actor);
  audit.log(auth.getName(),"WORKFLOW_DESIGN_REVISION","WORKFLOW",id.toString(),"SUCCESS","revision="+rev);
  return Map.of("id",id.toString(),"revision",rev,"status","DRAFT");
 }
 @PostMapping("/{id}/publish")
 @Transactional
 public Map<String,Object> publish(Authentication auth,@PathVariable UUID id,@RequestBody PublishInput input){
  Guard.requireAdmin(auth);
  if(input==null||input.revision()<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"流程版本无效");
  List<Long> version=jdbc.query("SELECT version FROM workflow_designs WHERE id=? FOR UPDATE",(r,n)->r.getLong(1),id.toString());
  if(version.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"流程不存在");
  if(version.get(0)!=input.expectedVersion())throw new ResponseStatusException(HttpStatus.CONFLICT,"流程已更新，请刷新后重试");
  List<String> jsons=jdbc.query("SELECT steps_json FROM workflow_design_versions WHERE workflow_id=? AND revision=? AND status='DRAFT' FOR UPDATE",(r,n)->r.getString(1),id.toString(),input.revision());
  Integer latest=jdbc.queryForObject("SELECT MAX(revision) FROM workflow_design_versions WHERE workflow_id=?",Integer.class,id.toString());
  if(jsons.size()!=1||latest==null||latest!=input.revision())throw new ResponseStatusException(HttpStatus.CONFLICT,"只能发布最新草稿");
  if(!digest(jsons.get(0)).equals(jdbc.queryForObject("SELECT graph_digest FROM workflow_design_versions WHERE workflow_id=? AND revision=?",String.class,id.toString(),input.revision())))throw new ResponseStatusException(HttpStatus.CONFLICT,"流程版本校验失败");
  jdbc.update("UPDATE workflow_design_versions SET status='RETIRED' WHERE workflow_id=? AND status='PUBLISHED'",id.toString());
  jdbc.update("UPDATE workflow_design_versions SET status='PUBLISHED',published_by=?,published_at=NOW(3) WHERE workflow_id=? AND revision=?",userId(auth),id.toString(),input.revision());
  jdbc.update("UPDATE workflow_designs SET version=version+1 WHERE id=? AND version=?",id.toString(),input.expectedVersion());
  audit.log(auth.getName(),"WORKFLOW_DESIGN_PUBLISHED","WORKFLOW",id.toString(),"SUCCESS","metadata only; flowable deployment disabled");
  return Map.of("status","PUBLISHED","revision",input.revision(),"flowableDeploymentEnabled",false);
 }
 private void validate(DesignInput in,boolean create){
  if(in==null||in.name()==null||!in.name().matches("[\\p{L}\\p{N} _-]{2,80}")||in.steps()==null||in.steps().size()<2||in.steps().size()>6||
    (create&&(in.code()==null||!in.code().matches("[a-z][a-z0-9_-]{2,99}"))))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"流程名称、编码或节点数量无效");
  for(Step step:in.steps())if(step==null||step.role()==null||!step.role().matches("[a-z][a-z0-9_-]{2,31}")||step.label()==null||!step.label().matches("[\\p{L}\\p{N} _-]{2,80}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"审批节点无效");
  if(in.highRiskOnlyLast()&&in.steps().size()<3)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"高风险审批至少需要三个节点");
 }
 private void insertVersion(String id,int revision,DesignInput in,String json,String actor){jdbc.update("INSERT INTO workflow_design_versions(id,workflow_id,revision,steps_json,graph_digest,high_risk_only_last,created_by) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,revision,json,digest(json),in.highRiskOnlyLast(),actor);}
 private String json(List<Step> steps){try{return mapper.writeValueAsString(steps);}catch(Exception ex){throw new IllegalStateException(ex);}}
 private static String digest(String json){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
 private String userId(Authentication auth){return jdbc.queryForObject("SELECT id FROM app_users WHERE username=?",String.class,auth.getName());}
}
