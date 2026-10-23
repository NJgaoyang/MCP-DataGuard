package io.github.mcpdataguard.api;

import io.github.mcpdataguard.workflow.contract.SafeApprovalGraph;
import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

/**
 * Safe visual designer for the only certified two-stage BPMN route.
 * No arbitrary XML, expression, service task, script task, or dynamic code upload.
 */
@RestController
@RequestMapping("/api/v1/workflow-designs")
public class WorkflowDesignController {
 private final JdbcTemplate jdbc;
 private final UserPrincipalResolver users;
 public WorkflowDesignController(JdbcTemplate jdbc,UserPrincipalResolver users){
  this.jdbc=jdbc;this.users=users;
 }
 public record DesignInput(String code,String name,String ownerLabel,String dbaLabel,
    List<SafeApprovalGraph.Step> steps,boolean highRiskOnlyLast){
  public DesignInput(String code,String name,String ownerLabel,String dbaLabel){
   this(code,name,ownerLabel,dbaLabel,null,false);
  }
 }
 public record PublishInput(int revision,long expectedVersion){}
 private UserPrincipal admin(Jwt jwt){
  UserPrincipal u=users.requireUser(jwt);users.requirePermission(u,"identity:admin");return u;
 }
 private static boolean code(String v){return v!=null&&v.matches("[a-z][a-z0-9_-]{2,99}");}
 private static boolean label(String v){return v!=null&&v.length()<=80&&v.matches("[\\p{L}\\p{N} _-]{2,80}");}
 private static String xml(String owner,String dba){
  return """
    <?xml version="1.0" encoding="UTF-8"?>
    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
       xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://mcp-dataguard.dev/workflows">
      <process id="sqlTwoLevelReview" name="SQL two-level review" isExecutable="true">
       <startEvent id="start"/>
       <sequenceFlow id="f1" sourceRef="start" targetRef="ownerReview"/>
       <userTask id="ownerReview" name="%s" flowable:candidateGroups="db-owner"/>
       <sequenceFlow id="f2" sourceRef="ownerReview" targetRef="dbaReview"/>
       <userTask id="dbaReview" name="%s" flowable:candidateGroups="dba"/>
       <sequenceFlow id="f3" sourceRef="dbaReview" targetRef="end"/>
       <endEvent id="end"/>
      </process>
    </definitions>
    """.formatted(owner,dba);
 }
 private static String xml(DesignInput input){
  if(input.steps()!=null){
   try{return SafeApprovalGraph.compile(
      new SafeApprovalGraph.Design(input.steps(),input.highRiskOnlyLast()));}
   catch(SecurityException e){
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsafe workflow graph");
   }
  }
  if(!label(input.ownerLabel())||!label(input.dbaLabel()))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid workflow task labels");
  return xml(input.ownerLabel(),input.dbaLabel());
 }
 private static String processKey(DesignInput input){
  return input.steps()!=null?"safeWorkflowV2":"sqlTwoLevelReview";
 }
 private static String digest(String xml){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
       .digest(xml.getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
 }
 @GetMapping
 public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt){
  UserPrincipal u=admin(jwt);
  return jdbc.query("""
    SELECT w.id,w.code,w.name,w.version,
      COALESCE(MAX(v.revision),0) AS revision,
      COALESCE(MAX(CASE WHEN v.status='PUBLISHED' THEN v.revision END),0) AS published
    FROM dg_workflow_template w LEFT JOIN dg_workflow_version v
       ON v.org_id=w.org_id AND v.workflow_id=w.id
    WHERE w.org_id=? AND w.state='ACTIVE'
    GROUP BY w.id,w.code,w.name,w.version
    ORDER BY w.name,w.id LIMIT 100
    """,(rs,i)->Map.<String,Object>of("id",rs.getString("id"),
       "code",rs.getString("code"),"name",rs.getString("name"),
       "version",rs.getLong("version"),"revision",rs.getInt("revision"),
       "publishedRevision",rs.getInt("published")),u.orgId());
 }
 @GetMapping("/{id}/versions")
 public List<Map<String,Object>> versions(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable UUID id){
  UserPrincipal u=admin(jwt);
  return jdbc.query("""
    SELECT v.revision,v.status,v.bpmn_xml,v.bpmn_digest,
      v.flowable_definition_key,v.published_at
    FROM dg_workflow_version v
    JOIN dg_workflow_template w ON w.org_id=v.org_id AND w.id=v.workflow_id
    WHERE v.org_id=? AND v.workflow_id=? AND w.state='ACTIVE'
    ORDER BY v.revision DESC LIMIT 30
    """,(rs,i)->{
      String content=rs.getString("bpmn_xml");
      String digest=rs.getString("bpmn_digest");
      if(!java.security.MessageDigest.isEqual(digest(content).getBytes(StandardCharsets.US_ASCII),
           digest.getBytes(StandardCharsets.US_ASCII)))
       throw new ResponseStatusException(HttpStatus.CONFLICT,"Published workflow version altered");
      boolean modern="safeWorkflowV2".equals(rs.getString("flowable_definition_key"));
      var graph=modern?SafeApprovalGraph.parseCertified(content):null;
      return Map.<String,Object>of(
       "revision",rs.getInt("revision"),"status",rs.getString("status"),
       "processKey",rs.getString("flowable_definition_key"),
       "steps",modern?graph.steps():List.of(
          new SafeApprovalGraph.Step("db-owner","Data Owner"),
          new SafeApprovalGraph.Step("dba","DBA Review")),
       "highRiskOnlyLast",modern&&graph.highRiskOnlyLast()
      );
    },u.orgId(),id);
 }
 @PostMapping
 @Transactional
 public Map<String,Object> create(@AuthenticationPrincipal Jwt jwt,@RequestBody DesignInput input){
  UserPrincipal u=admin(jwt);
  if(input==null||!code(input.code())||!label(input.name()))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid workflow labels or code");
  UUID id=UUID.randomUUID(),revisionId=UUID.randomUUID();
  String bpmn=xml(input);
  jdbc.update("""
   INSERT INTO dg_workflow_template(id,org_id,code,name,created_by)
   VALUES(?,?,?,?,?)
   """,id,u.orgId(),input.code(),input.name(),u.userId());
  jdbc.update("""
   INSERT INTO dg_workflow_version(id,org_id,workflow_id,revision,bpmn_xml,
     bpmn_digest,flowable_definition_key,status)
   VALUES(?,?,?,1,?,? ,?,'DRAFT')
   """,revisionId,u.orgId(),id,bpmn,digest(bpmn),processKey(input));
  audit(u,"WORKFLOW_DESIGN_CREATED",id);
  return Map.of("id",id.toString(),"revision",1,"status","DRAFT");
 }
 @PostMapping("/{id}/draft")
 @Transactional
 public Map<String,Object> draft(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
                                 @RequestBody DesignInput input){
  UserPrincipal u=admin(jwt);
  if(input==null||!label(input.name()))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid workflow labels");
  List<UUID> locked=jdbc.query("""
    SELECT id FROM dg_workflow_template
    WHERE org_id=? AND id=? AND state='ACTIVE' FOR UPDATE
    """,(rs,i)->rs.getObject(1,UUID.class),u.orgId(),id);
  if(locked.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Design not found");
  List<Integer> versions=jdbc.query("""
    SELECT COALESCE(MAX(revision),0) FROM dg_workflow_version
    WHERE org_id=? AND workflow_id=?
    """,(rs,i)->rs.getInt(1),u.orgId(),id);
  if(versions.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Design not found");
  int next=versions.get(0)+1;
  String bpmn=xml(input.ownerLabel(),input.dbaLabel());
  jdbc.update("""
    INSERT INTO dg_workflow_version(id,org_id,workflow_id,revision,bpmn_xml,
      bpmn_digest,flowable_definition_key,status)
    VALUES(?,?,?,?,?,?,?,'DRAFT')
    """,UUID.randomUUID(),u.orgId(),id,next,bpmn,digest(bpmn),processKey(input));
  jdbc.update("UPDATE dg_workflow_template SET name=?,version=version+1,updated_at=now() WHERE org_id=? AND id=?",
    input.name(),u.orgId(),id);
  audit(u,"WORKFLOW_DESIGN_REVISION",id);
  return Map.of("id",id.toString(),"revision",next,"status","DRAFT");
 }
 @PostMapping("/{id}/publish")
 @Transactional
 public Map<String,Object> publish(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
     @RequestBody PublishInput input){
  UserPrincipal u=admin(jwt);
  if(input==null||input.revision()<1||input.expectedVersion()<0)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid publish version");
  List<Integer> matching=jdbc.query("""
    SELECT revision FROM dg_workflow_version WHERE org_id=? AND workflow_id=?
      AND revision=? AND status='DRAFT' FOR UPDATE
    """,(rs,i)->rs.getInt(1),u.orgId(),id,input.revision());
  if(matching.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Draft revision unavailable");
  Integer latest=jdbc.queryForObject("""
    SELECT MAX(revision) FROM dg_workflow_version
    WHERE org_id=? AND workflow_id=?
    """,Integer.class,u.orgId(),id);
  if(latest==null||latest!=input.revision())
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Only latest draft revision can publish");
  int updated=jdbc.update("""
    UPDATE dg_workflow_template SET version=version+1,updated_at=now()
    WHERE org_id=? AND id=? AND state='ACTIVE' AND version=?
    """,u.orgId(),id,input.expectedVersion());
  if(updated!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Concurrent edit");
  jdbc.update("""
    UPDATE dg_workflow_version SET status='RETIRED'
    WHERE org_id=? AND workflow_id=? AND status='PUBLISHED'
    """,u.orgId(),id);
  jdbc.update("""
    UPDATE dg_workflow_version SET status='PUBLISHED',published_by=?,published_at=now()
    WHERE org_id=? AND workflow_id=? AND revision=? AND status='DRAFT'
    """,u.userId(),u.orgId(),id,input.revision());
  audit(u,"WORKFLOW_DESIGN_PUBLISHED",id);
  // Publishing is metadata-only; a separate certified deployment gate is required
  // before a newly authored BPMN revision is activated inside Flowable.
  return Map.of("status","PUBLISHED","revision",input.revision(),
    "flowableDeploymentEnabled",false,"sqlExecutionEnabled",false);
 }
 private void audit(UserPrincipal u,String action,UUID id){
  jdbc.update("""
    INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id)
    VALUES (?,?,?,?,'WORKFLOW_DESIGN',?)
    """,UUID.randomUUID(),u.orgId(),u.userId().toString(),action,id.toString());
 }
}
