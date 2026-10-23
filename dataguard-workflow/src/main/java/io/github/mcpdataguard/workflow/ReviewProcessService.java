package io.github.mcpdataguard.workflow;

import java.util.List;
import java.util.Map;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal Flowable workflow service. No HTTP endpoints expose this component.
 * Approval signals are workflow state only, NEVER SQL execution authorization.
 */
@Service
public class ReviewProcessService {
 private final RuntimeService runtime;
 private final TaskService tasks;
 public ReviewProcessService(RuntimeService runtime,TaskService tasks){
  this.runtime=runtime;this.tasks=tasks;
 }
 @Transactional
 public String start(String requestKey,String orgId){
  if(requestKey==null || !requestKey.matches("[a-zA-Z0-9_-]{8,128}") ||
     orgId==null || !orgId.matches("[0-9a-f-]{36}"))
   throw new IllegalArgumentException("Invalid workflow identity");
  ProcessInstance current=runtime.createProcessInstanceQuery()
    .processInstanceBusinessKey(requestKey).singleResult();
  if(current!=null)throw new IllegalStateException("Workflow already active");
  return runtime.startProcessInstanceByKey("sqlTwoLevelReview",requestKey,
     Map.of("tenantId",orgId,"sqlExecutionEnabled",false)).getId();
 }
 public List<ReviewTask> pending(String processInstanceId){
  requireId(processInstanceId);
  return tasks.createTaskQuery().processInstanceId(processInstanceId).list().stream()
    .map(t->new ReviewTask(t.getId(),t.getName(),t.getTaskDefinitionKey())).toList();
 }
 @Transactional
 public void complete(String taskId,String assignedReviewer){
  requireId(taskId);
  requireId(assignedReviewer);
  Task task=tasks.createTaskQuery().taskId(taskId).singleResult();
  if(task==null)throw new IllegalArgumentException("Task unavailable");
  if(task.getAssignee()==null || !assignedReviewer.equals(task.getAssignee()))
   throw new SecurityException("Task is not assigned to reviewer");
  tasks.complete(taskId,Map.of("lastReviewer",assignedReviewer,"sqlExecutionEnabled",false));
 }
 @Transactional
 public void reject(String taskId,String assignedReviewer,String reason){
  requireId(taskId);requireId(assignedReviewer);
  if(reason==null || reason.isBlank() || reason.length()>1000)
   throw new IllegalArgumentException("Reason required");
  Task task=tasks.createTaskQuery().taskId(taskId).singleResult();
  if(task==null || task.getAssignee()==null || !assignedReviewer.equals(task.getAssignee()))
   throw new SecurityException("Reviewer does not own task");
  runtime.deleteProcessInstance(task.getProcessInstanceId(),"REJECTED: "+reason.strip());
 }
 private static void requireId(String id){
  if(id==null || !id.matches("[a-zA-Z0-9_-]{1,128}"))
   throw new IllegalArgumentException("Invalid identifier");
 }
 public record ReviewTask(String taskId,String name,String stage){}
}
