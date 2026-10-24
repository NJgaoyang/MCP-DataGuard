package io.github.mcpdataguard.standalone;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalApi {
 private final JdbcTemplate jdbc;
 private final RuntimeService runtime;
 private final TaskService tasks;
 private final AuditService audit;
 public ApprovalApi(JdbcTemplate jdbc,RuntimeService runtime,TaskService tasks,AuditService audit){
  this.jdbc=jdbc;this.runtime=runtime;this.tasks=tasks;this.audit=audit;
 }
 public record NewRequest(String sourceId,String tableName,String approverId,String reason){}
 @PostMapping
 @Transactional
 public Map<String,Object> request(Authentication auth,@RequestBody NewRequest in){
  if(in==null||in.reason()==null||in.reason().isBlank()||in.reason().length()>1000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reason required");
  String table=Guard.identifier(in.tableName());
  UUID source=UUID.fromString(in.sourceId());
  UUID approver=UUID.fromString(in.approverId());
  String requester=jdbc.queryForObject("SELECT id FROM app_users WHERE username=? AND enabled=1",String.class,auth.getName());
  if(requester.equals(approver.toString()))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot self approve");
  List<String> reviewers=jdbc.query("SELECT username FROM app_users WHERE id=? AND enabled=1 AND role_code IN ('APPROVER','ADMIN') AND must_change_password=0",
    (r,i)->r.getString(1),approver.toString());
  if(reviewers.size()!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Approver must be active internal user");
  Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM read_table_allowlist w JOIN data_sources s ON w.source_id=s.id WHERE s.id=? AND s.enabled=1 AND w.table_name=? AND w.enabled=1",Integer.class,source.toString(),table);
  if(count==null||count!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Table not allowlisted");
  String id=UUID.randomUUID().toString();
  var instance=runtime.startProcessInstanceByKey("internalReadApproval",id,Map.of("approverUsername",reviewers.get(0)));
  jdbc.update("INSERT INTO approval_requests(id,requester_user_id,approver_user_id,source_id,table_name,reason,status,process_instance_id,expires_at) "+
      "VALUES(?,?,?,?,?,?,'PENDING',?,DATE_ADD(NOW(3),INTERVAL 24 HOUR))",
    id,requester,approver.toString(),source.toString(),table,in.reason().strip(),instance.getId());
  audit.log(auth.getName(),"APPROVAL_REQUESTED","APPROVAL",id,"SUCCESS","table="+table);
  return Map.of("id",id,"status","PENDING","approver",reviewers.get(0));
 }
 @GetMapping("/my")
 public List<Map<String,Object>> inbox(Authentication auth){
  return jdbc.queryForList("SELECT a.id,a.table_name AS tableName,a.reason,a.status,a.created_at AS createdAt,u.username AS requester "+
      "FROM approval_requests a JOIN app_users u ON u.id=a.requester_user_id "+
      "JOIN app_users reviewer ON reviewer.id=a.approver_user_id "+
      "WHERE reviewer.username=? AND a.status='PENDING' ORDER BY a.created_at DESC LIMIT 100",auth.getName());
 }
 public record Decision(String reason){}
 @PostMapping("/{id}/approve")
 @Transactional
 public Map<String,Object> approve(Authentication auth,@PathVariable UUID id,@RequestBody Decision in){
  return decide(auth,id,in,true);
 }
 @PostMapping("/{id}/reject")
 @Transactional
 public Map<String,Object> reject(Authentication auth,@PathVariable UUID id,@RequestBody Decision in){
  return decide(auth,id,in,false);
 }
 private Map<String,Object> decide(Authentication auth,UUID id,Decision input,boolean yes){
  if(input==null||input.reason()==null||input.reason().isBlank()||input.reason().length()>1000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Decision reason required");
  List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.process_instance_id,a.requester_user_id "+
    "FROM approval_requests a JOIN app_users u ON u.id=a.approver_user_id "+
    "WHERE a.id=? AND a.status='PENDING' AND a.expires_at>NOW(3) "+
    "AND u.username=? AND u.enabled=1 AND u.role_code IN ('APPROVER','ADMIN') AND u.must_change_password=0 FOR UPDATE",id.toString(),auth.getName());
  if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Task unavailable");
  String process=(String)rows.get(0).get("process_instance_id");
  var task=tasks.createTaskQuery().processInstanceId(process).taskAssignee(auth.getName()).singleResult();
  if(task==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Flowable task unavailable");
  if(yes)tasks.complete(task.getId());
  else runtime.deleteProcessInstance(process,"Rejected by assigned reviewer");
  jdbc.update("UPDATE approval_requests SET status=?,decided_at=NOW(3) WHERE id=? AND status='PENDING'",
    yes?"APPROVED":"REJECTED",id.toString());
  audit.log(auth.getName(),yes?"APPROVAL_APPROVED":"APPROVAL_REJECTED","APPROVAL",id.toString(),"SUCCESS",input.reason().strip());
  return Map.of("id",id.toString(),"status",yes?"APPROVED":"REJECTED");
 }
}