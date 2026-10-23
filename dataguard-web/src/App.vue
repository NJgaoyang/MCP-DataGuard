<script setup lang="ts">
import {ref,computed,reactive} from 'vue';
import {message} from 'ant-design-vue';
const pages=[
 ['dashboard','总览仪表盘'],['agents','Agent 管理'],['datasources','数据源管理'],
 ['rules','SQL 安全规则'],['flowable','Flowable 审批'],['audit','SQL 审计与监控'],
 ['users','用户与权限']
];
const selected=ref('dashboard');
const sqlPolicies=ref<{id:string;code:string;name:string;status:string;version:number;latestVersion:number}[]>([]);
const policyDialog=ref(false);
const operationalSummary=ref<{deniedRequests:number;pendingApprovals:number;openAlerts:number}|null>(null);
type DailyTrend={day:string;requests:number;denied:number;completed:number;approvals:number;approved:number;alerts:number};
const dailyTrends=ref<DailyTrend[]>([]);
function trendPercent(value:number){
 const top=Math.max(1,...dailyTrends.value.map(day=>day.requests));
 return Math.max(2,Math.min(100,Math.round(value/top*100)))+'%';
}
async function loadTrends(){
 try{dailyTrends.value=await getData<DailyTrend[]>('/api/v1/operations/trends');}
 catch(e){error.value='查询监控趋势失败：'+(e as Error).message;}
}

const sqlRequestRows=ref<Record<string,unknown>[]>([]);
const approvalRows=ref<Record<string,unknown>[]>([]);
const workflowDesigns=ref<{id:string;code:string;name:string;version:number;revision:number;publishedRevision:number}[]>([]);
const workflowDesignerOpen=ref(false);
const workflowDesignTarget=ref<{id:string;version:number}|null>(null);
const workflowDesignForm=reactive({code:'',name:'SQL 双级审批',ownerLabel:'数据源负责人',dbaLabel:'DBA 审核'});
type WorkflowStep={role:string;label:string};
const workflowSteps=ref<WorkflowStep[]>([
 {role:'db-owner',label:'数据源负责人'},
 {role:'dba',label:'DBA 审核'}
]);
const workflowHighRiskLast=ref(false);
const workflowDragging=ref<number|null>(null);
function addWorkflowStep(){
 if(workflowSteps.value.length>=6)return;
 workflowSteps.value.push({role:'review-'+(workflowSteps.value.length+1),label:'审批节点 '+(workflowSteps.value.length+1)});
}
function removeWorkflowStep(index:number){
 if(workflowSteps.value.length<=2)return;
 workflowSteps.value.splice(index,1);
 if(workflowSteps.value.length<3)workflowHighRiskLast.value=false;
}
function moveWorkflowStep(from:number,to:number){
 if(to<0||to>=workflowSteps.value.length||from===to)return;
 const [step]=workflowSteps.value.splice(from,1);
 workflowSteps.value.splice(to,0,step);
}
function dropWorkflowStep(to:number){
 if(workflowDragging.value!==null)moveWorkflowStep(workflowDragging.value,to);
 workflowDragging.value=null;
}

async function loadWorkflowDesigns(){
 try{workflowDesigns.value=await getData<typeof workflowDesigns.value>('/api/v1/workflow-designs');}
 catch(e){error.value='读取流程设计失败：'+(e as Error).message;}
}
async function saveWorkflowDesign(){
 saving.value=true;error.value='';
 try{
  if(workflowDesignTarget.value){
   await requestWrite('/api/v1/workflow-designs/'+encodeURIComponent(workflowDesignTarget.value.id)+'/draft',
      'POST',{...workflowDesignForm,steps:workflowSteps.value,highRiskOnlyLast:workflowHighRiskLast.value});
  }else{
   await requestWrite('/api/v1/workflow-designs','POST',
     {...workflowDesignForm,steps:workflowSteps.value,highRiskOnlyLast:workflowHighRiskLast.value});
  }
  message.success('工作流草稿已保存；未启用任何 SQL 执行');
  workflowDesignerOpen.value=false;await loadWorkflowDesigns();
 }catch(e){error.value=(e as Error).message;}finally{saving.value=false;}
}
async function publishWorkflowDesign(row:{id:string;revision:number;version:number}){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/workflow-designs/'+encodeURIComponent(row.id)+'/publish','POST',
    {revision:row.revision,expectedVersion:row.version});
  message.success('已发布流程元数据，Flowable 动态部署仍保持关闭');
  await loadWorkflowDesigns();
 }catch(e){error.value=(e as Error).message;}finally{saving.value=false;}
}
function workflowDesignOpenReset(){
 workflowDesignForm.code='';workflowDesignForm.name='SQL 双级审批';
 workflowDesignForm.ownerLabel='数据源负责人';workflowDesignForm.dbaLabel='DBA 审核';
 workflowSteps.value=[{role:'db-owner',label:'数据源负责人'},
   {role:'dba',label:'DBA 审核'}];workflowHighRiskLast.value=false;
 workflowDesignerOpen.value=true;
}
async function editWorkflowDesign(row:{id:string;code:string;name:string;version:number}){
 workflowDesignTarget.value={id:row.id,version:row.version};
 workflowDesignForm.code=row.code;workflowDesignForm.name=row.name;
 error.value='';
 try{
  const versions=await getData<{revision:number;status:string;processKey:string;
    steps:WorkflowStep[];highRiskOnlyLast:boolean}[]>(
    '/api/v1/workflow-designs/'+encodeURIComponent(row.id)+'/versions');
  if(versions.length){
   workflowSteps.value=versions[0].steps.map(x=>({...x}));
   workflowHighRiskLast.value=versions[0].highRiskOnlyLast;
  }
  workflowDesignerOpen.value=true;
 }catch(e){error.value='读取流程修订失败：'+(e as Error).message;}
}

const deadLetters=ref<{id:string;type:string;approvalId:string;attempts:number;createdAt:string}[]>([]);
async function loadDeadLetters(){
 try{deadLetters.value=await getData<typeof deadLetters.value>('/api/v1/workflow-events/dead-letters');}
 catch(e){error.value='读取审批死信失败：'+(e as Error).message;}
}
async function retryDeadLetter(id:string){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/workflow-events/'+encodeURIComponent(id)+'/retry','POST');
  message.success('事件已加入安全重试队列');await loadDeadLetters();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
const auditVerification=ref('');
async function verifyAuditStream(stream:string){
 try{
  const result=await getData<{verified:boolean;code:string;checked?:number}>(
    '/api/v1/audit-integrity?stream='+encodeURIComponent(stream));
  auditVerification.value=(result.verified?'完整性验证通过：':'完整性验证失败：')
    +result.code+'（'+String(result.checked??0)+' 条）';
 }catch(e){error.value='验证审计链失败：'+(e as Error).message;}
}
const securityAlerts=ref<{id:string;status:string;severity:string;rule:string;observedAt:string}[]>([]);
async function loadSecurityAlerts(){
 try{securityAlerts.value=await getData<typeof securityAlerts.value>('/api/v1/security-alerts');}
 catch(e){error.value='查询告警失败：'+(e as Error).message;}
}
async function acknowledgeAlert(id:string){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/security-alerts/'+encodeURIComponent(id)+'/acknowledge','POST');
  message.success('告警已确认');await loadSecurityAlerts();await loadOperations();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}

const approvalDialog=ref(false);
const approving=ref({id:'',version:0});
const approvalReason=ref('');
async function approveTask(){
 if(!approvalReason.value.trim()){error.value='请输入审批意见';return;}
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/approvals/'+encodeURIComponent(approving.value.id)+'/approve','POST',
    {reason:approvalReason.value,expectedVersion:approving.value.version});
  approvalDialog.value=false;approvalReason.value='';
  message.success('审批已记录，正在通过可靠事件同步到 Flowable；不会执行写 SQL');
  await loadOperations();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
const rejectionDialog=ref(false);
const rejectingApproval=ref({id:'',version:0});
const rejectionReason=ref('');
async function rejectApproval(){
 if(!rejectionReason.value.trim()){error.value='请输入驳回原因';return;}
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/approvals/'+encodeURIComponent(rejectingApproval.value.id)+'/reject','POST',
    {reason:rejectionReason.value,expectedVersion:rejectingApproval.value.version});
  rejectionDialog.value=false;rejectionReason.value='';message.success('工单已拒绝并阻止执行');
  await loadOperations();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}

async function loadOperations(){
 try{
  const [summary,requests,approvals]=await Promise.all([
   getData<{deniedRequests:number;pendingApprovals:number;openAlerts:number}>('/api/v1/operations/summary'),
   getData<Record<string,unknown>[]>('/api/v1/operations/requests'),
   getData<Record<string,unknown>[]>('/api/v1/operations/approvals')
  ]);
  operationalSummary.value=summary;sqlRequestRows.value=requests;approvalRows.value=approvals;
 }catch(e){error.value='读取安全监控失败：'+(e as Error).message;}
}
const systemEvents=ref<{id:string;actor:string;operation:string;resourceType:string|null;resourceId:string|null;createdAt:string}[]>([]);
async function loadAuditEvents(){
 try{systemEvents.value=await getData<typeof systemEvents.value>('/api/v1/system-events?limit=100');}
 catch(e){error.value='查询系统操作审计失败：'+(e as Error).message;}
}
const policyForm=reactive({code:'',name:''});
const policyRuleDialog=ref(false);
const policyTarget=ref({id:'',name:'',status:'',version:0,latestVersion:0});
const policyRule=reactive({decision:'DENY',priority:100,table:'',environment:'DEV',operation:'SELECT'});
const policyVersions=ref<{revision:number;priority:number;decision:string;rule:string;published:boolean}[]>([]);
const policySimulation=ref('');
const effectivePolicyScope=reactive({table:'orders',environment:'TEST',operation:'SELECT'});
const effectivePolicyResult=ref('');
async function evaluateEffectivePolicies(){
 saving.value=true;error.value='';effectivePolicyResult.value='';
 try{
  const v=await requestWrite('/api/v1/sql-policies/effective-decision','POST',
    effectivePolicyScope) as unknown as
     {decision:string;matched:boolean;evaluatedRuleCount:number;matchedRules:{code:string;priority:number;decision:string}[]};
  effectivePolicyResult.value='最终决策：'+v.decision+'；匹配 '+v.evaluatedRuleCount+' 条已发布规则。'+
    v.matchedRules.map(x=>x.code+'（优先级 '+x.priority+'，'+x.decision+'）').join(' → ');
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}

const token=ref('');
const loginForm=reactive({username:'',password:''});
const mustChange=ref(false);
const forcedPasswordChange=ref(false);
const changeForm=reactive({currentPassword:'',newPassword:''});
const agentTokenOnce=ref('');
const maskDialog=ref(false);
const maskTarget=ref({id:'',name:''});
const maskForm=reactive({table:'',column:'',algorithm:'REDACT',priority:100,roleCode:''});
const maskRows=ref<{id:string;table:string;column:string;algorithm:string;priority:number;roleCode:string}[]>([]);
async function loadMaskRules(){
 if(!maskTarget.value.id)return;
 try{maskRows.value=await getData<typeof maskRows.value>(
  '/api/v1/mask-rules?datasourceId='+encodeURIComponent(maskTarget.value.id));}
 catch(e){error.value='查询脱敏规则失败：'+(e as Error).message;}
}
async function openMaskDialog(record:Record<string,unknown>){
 maskTarget.value={id:String(record.id),name:String(record.name)};
 maskDialog.value=true;await loadMaskRules();
}
async function saveMaskRule(){
 saving.value=true;error.value='';
 try{
  if(!/^[a-z][a-z0-9_]{0,254}$/.test(maskForm.table)||
     !/^[a-z][a-z0-9_]{0,254}$/.test(maskForm.column))
   throw new Error('请输入精确的表名和字段名');
  await requestWrite('/api/v1/mask-rules','POST',{
   datasourceId:maskTarget.value.id,...maskForm,
    roleCode:maskForm.roleCode.trim()||null});
  message.success('脱敏规则已保存');await loadMaskRules();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function disableMaskRule(id:string){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/mask-rules/'+encodeURIComponent(id),'DELETE');
  message.success('脱敏规则已停用');await loadMaskRules();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
const grantDialog=ref(false);
const grantAgent=ref({id:'',name:''});
const grantForm=reactive({datasourceId:'',table:'',column:'',action:'ALLOW' as 'ALLOW'|'DENY'});
const schemaGrants=ref<{datasourceId:string;table:string;action:string}[]>([]);
const columnGrants=ref<{table:string;column:string;action:string}[]>([]);
const grantLoading=ref(false);
const profile=ref<{orgId:string;userId:string;username:string;displayName:string}|null>(null);
const agents=ref<Record<string,unknown>[]>([]);
const usersList=ref<Record<string,unknown>[]>([]);
const rolesList=ref<{id:string;code:string;name:string;permissions:string;systemRole:boolean}[]>([]);
const userDialog=ref(false),roleDialog=ref(false),enrollDialog=ref(false),credentialDialog=ref(false);
const userForm=reactive({username:'',displayName:'',password:''});
const roleForm=reactive({code:'',name:'',permissions:['agent:read','datasource:read'] as string[]});
const enrollForm=reactive({agentId:''});
const credentialForm=reactive({datasourceId:'',secretRef:''});
const assignment=reactive({userId:'',roleId:''});
const datasources=ref<Record<string,unknown>[]>([]);
const busy=ref(false),error=ref('');
const title=computed(()=>pages.find(p=>p[0]===selected.value)?.[1]||'总览仪表盘');
const agentDialog=ref(false),sourceDialog=ref(false),saving=ref(false);
const agentForm=reactive({
 id:'',code:'',name:'',description:'',rateLimitPerMinute:60,
 concurrencyLimit:4,maxRows:200,maxExecutionMs:10000,expectedVersion:0
});
const sourceForm=reactive({
 id:'',code:'',name:'',dbType:'POSTGRESQL',environment:'DEV',
 host:'',port:5432,databaseName:'',expectedVersion:0
});
const columnsAgents=[{title:'Agent 名称',dataIndex:'name'},{title:'标识',dataIndex:'code'},{title:'状态',dataIndex:'status'},{title:'每分钟请求数',dataIndex:'rateLimitPerMinute'},{title:'操作',key:'actions'}];
const columnsSources=[{title:'数据源',dataIndex:'name'},{title:'数据库类型',dataIndex:'databaseType'},{title:'环境',dataIndex:'environment'},{title:'状态',dataIndex:'status'},{title:'操作',key:'actions'}];
async function getData<T>(path:string):Promise<T>{
 const res=await fetch(path,{headers:{Authorization:'Bearer '+token.value,Accept:'application/json'},credentials:'omit'});
 if(!res.ok)throw new Error(path+' 返回 HTTP '+res.status);
 return await res.json() as T;
}
async function loadPolicies(){
 try{sqlPolicies.value=await getData<typeof sqlPolicies.value>('/api/v1/sql-policies');}
 catch(e){error.value=(e as Error).message;}
}
async function changePolicyState(row:{id:string;version:number},action:'publish'|'disable'){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/sql-policies/'+encodeURIComponent(row.id)+'/'+action,
    'POST',{expectedVersion:row.version});
  message.success(action==='publish'?'仅拒绝规则已发布，不授予 SQL 执行权限':'策略已停用');
  await loadPolicies();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function savePolicy(){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/sql-policies','POST',{...policyForm});
  policyDialog.value=false;message.success('SQL 策略草稿创建成功');
  await loadPolicies();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function loadPolicyVersions(){
 try{policyVersions.value=await getData<typeof policyVersions.value>('/api/v1/sql-policies/'+encodeURIComponent(policyTarget.value.id)+'/versions');}
 catch(e){error.value='读取规则版本失败：'+(e as Error).message;}
}
async function openPolicyRules(row:{id:string;name:string;status:string;version:number;latestVersion:number}){
 policyTarget.value={...row};
 policyRuleDialog.value=true;await loadPolicyVersions();
}
async function rollbackPolicy(revision:number){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/sql-policies/'+encodeURIComponent(policyTarget.value.id)+'/rollback','POST',
   {revision,expectedVersion:policyTarget.value.version});
  message.success('已从历史版本恢复拒绝策略，并生成新的发布版本');
  policyRuleDialog.value=false;await loadPolicies();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function simulatePolicyRule(){
 saving.value=true;error.value='';policySimulation.value='';
 try{
  const result=await requestWrite('/api/v1/sql-policies/'+encodeURIComponent(policyTarget.value.id)+'/simulate','POST',
   {table:policyRule.table,environment:policyRule.environment,operation:policyRule.operation}) as unknown as
   {matched:boolean;decision:string;revision?:number;simulationOnly:boolean};
  policySimulation.value=result.matched
   ?'命中草稿版本 '+result.revision+'，模拟决策：'+result.decision
   :'未匹配草稿规则，模拟结果：DENY（默认拒绝）';
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function savePolicyRule(){
 saving.value=true;error.value='';
 try{
  if(!/^[a-z][a-z0-9_]{0,254}$/.test(policyRule.table))throw new Error('请填写有效的表名');
  await requestWrite('/api/v1/sql-policies/'+encodeURIComponent(policyTarget.value.id)+'/versions','POST',{...policyRule});
  message.success('拒绝/审批规则草稿版本已保存，不会自动生效');
  await loadPolicyVersions();await loadPolicies();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function reload(){
 if(!token.value||mustChange.value)return;busy.value=true;error.value='';
 try{
  profile.value=await getData<typeof profile.value>('/api/v1/me');
  const results=await Promise.allSettled([
   getData<Record<string,unknown>[]>('/api/v1/agents?limit=100'),
   getData<Record<string,unknown>[]>('/api/v1/datasources?limit=100')
  ]);
  agents.value=results[0].status==='fulfilled'?results[0].value:[];
  datasources.value=results[1].status==='fulfilled'?results[1].value:[];
  if(results.some(x=>x.status==='rejected'))error.value='部分数据权限不足，请检查 agent:read 和 datasource:read 角色。';
 }catch(e){profile.value=null;error.value=(e as Error).message;}
 finally{busy.value=false;}
}

async function requestWrite(path:string,method:'POST'|'PUT'|'DELETE',payload?:unknown){
 const response=await fetch(path,{
  method,headers:{Authorization:'Bearer '+token.value,Accept:'application/json',
   ...(payload?{'Content-Type':'application/json'}:{})},
  credentials:'omit',body:payload?JSON.stringify(payload):undefined
 });
 if(!response.ok)throw new Error('写入接口返回 HTTP '+response.status+'。请检查写入角色、参数或版本号。');
 return await response.json() as {id:string;status:string;version:number};
}
function newAgent(){
 Object.assign(agentForm,{id:'',code:'',name:'',description:'',rateLimitPerMinute:60,
  concurrencyLimit:4,maxRows:200,maxExecutionMs:10000,expectedVersion:0});
 agentDialog.value=true;
}
async function editAgent(record:Record<string,unknown>){
 try{
  const dto=await getData<typeof agentForm & {version:number}>('/api/v1/agents/'+record.id+'/edit');
  Object.assign(agentForm,{id:dto.id,code:dto.code,name:dto.name,
   description:dto.description||'',rateLimitPerMinute:dto.rateLimitPerMinute,
   concurrencyLimit:dto.concurrencyLimit,maxRows:dto.maxRows,
   maxExecutionMs:dto.maxExecutionMs,expectedVersion:dto.version});
  agentDialog.value=true;
 }catch(e){error.value=(e as Error).message;}
}
async function saveAgent(){
 saving.value=true;error.value='';
 try{
  const {id,code,expectedVersion,...values}=agentForm;
  if(id)await requestWrite('/api/v1/agents/'+id,'PUT',{...values,expectedVersion});
  else await requestWrite('/api/v1/agents','POST',{...values,code});
  agentDialog.value=false;message.success('Agent 已保存，默认禁用，需单独注册可信身份与授权。');
  await reload();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function revokeAgent(record:Record<string,unknown>){
 try{
  await requestWrite('/api/v1/agents/'+record.id+'/revoke','POST');
  message.success('Agent 已撤销，其身份绑定同时失效。');await reload();
 }catch(e){error.value=(e as Error).message;}
}
function newDatasource(){
 Object.assign(sourceForm,{id:'',code:'',name:'',dbType:'POSTGRESQL',environment:'DEV',
  host:'',port:5432,databaseName:'',expectedVersion:0});
 sourceDialog.value=true;
}
async function editDatasource(record:Record<string,unknown>){
 try{
  const dto=await getData<typeof sourceForm & {version:number}>('/api/v1/datasources/'+record.id+'/edit');
  Object.assign(sourceForm,{id:dto.id,code:dto.code,name:dto.name,
   dbType:dto.dbType,environment:dto.environment,host:dto.host,port:dto.port,
   databaseName:dto.databaseName||'',expectedVersion:dto.version});
  sourceDialog.value=true;
 }catch(e){error.value=(e as Error).message;}
}
async function saveDatasource(){
 saving.value=true;error.value='';
 try{
  const {id,code,dbType,environment,expectedVersion,...values}=sourceForm;
  const payload={...values,databaseName:values.databaseName.trim()||null};
  if(id)await requestWrite('/api/v1/datasources/'+id,'PUT',{...payload,expectedVersion});
  else await requestWrite('/api/v1/datasources','POST',{...payload,code,dbType,environment});
  sourceDialog.value=false;message.success('数据源配置已保存，连接默认禁用。');
  await reload();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function disableDatasource(record:Record<string,unknown>){
 try{
  await requestWrite('/api/v1/datasources/'+record.id+'/disable','POST');
  message.success('数据源已停用。');await reload();
 }catch(e){error.value=(e as Error).message;}
}

async function loadAdmin(){
 if(!token.value||mustChange.value)return;
 try{
  const result=await Promise.all([
   getData<Record<string,unknown>[]>('/api/v1/admin/users'),
   getData<typeof rolesList.value>('/api/v1/admin/roles')
  ]);
  usersList.value=result[0];rolesList.value=result[1];
 }catch(e){error.value='身份管理需要 identity:admin 权限：'+(e as Error).message;}
}
async function saveUser(){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/admin/users','POST',{...userForm});
  userDialog.value=false;userForm.password='';message.success('内置用户创建成功；请为其分配角色。');
  await loadAdmin();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function saveRole(){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/admin/roles','POST',{...roleForm});
  roleDialog.value=false;message.success('角色创建成功。');
  await loadAdmin();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function assignRole(){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/admin/users/'+assignment.userId+'/roles/'+assignment.roleId,'POST');
  message.success('组织内角色已分配。');await loadAdmin();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
function openEnroll(record:Record<string,unknown>){
 Object.assign(enrollForm,{agentId:String(record.id)});agentTokenOnce.value='';
 enrollDialog.value=true;
}
async function saveEnroll(){
 saving.value=true;error.value='';
 try{
  const data=await requestWrite('/api/v1/agents/'+enrollForm.agentId+'/native-token','POST');
  agentTokenOnce.value=(data as unknown as {token:string}).token;
  message.success('Agent 令牌仅显示一次，请立即安全保存。');
  await reload();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function loadGrants(){
 if(!grantAgent.value.id||!grantForm.datasourceId)return;
 grantLoading.value=true;
 try{
  const base='/api/v1/agents/'+encodeURIComponent(grantAgent.value.id);
  const results=await Promise.all([
   getData<typeof schemaGrants.value>(base+'/schema-permissions'),
   getData<typeof columnGrants.value>(base+'/column-grants?datasourceId='+encodeURIComponent(grantForm.datasourceId))
  ]);
  schemaGrants.value=results[0].filter(x=>x.datasourceId===grantForm.datasourceId);
  columnGrants.value=results[1];
 }catch(e){error.value='读取授权失败：'+(e as Error).message;}
 finally{grantLoading.value=false;}
}
async function openGrants(record:Record<string,unknown>){
 grantAgent.value={id:String(record.id),name:String(record.name)};
 Object.assign(grantForm,{datasourceId:'',table:'',column:'',action:'ALLOW'});
 schemaGrants.value=[];columnGrants.value=[];grantDialog.value=true;
}
async function saveGrant(scope:'table'|'column'){
 if(!grantForm.datasourceId||!grantForm.table||!/^[a-z][a-z0-9_]{0,254}$/.test(grantForm.table) ||
    (scope==='column'&&!/^[a-z][a-z0-9_]{0,254}$/.test(grantForm.column))){
  error.value='请选择数据源并填写符合规范的表名、字段名。';return;
 }
 saving.value=true;error.value='';
 try{
  const base='/api/v1/agents/'+encodeURIComponent(grantAgent.value.id);
  if(scope==='table')await requestWrite(base+'/schema-permissions','POST',{
   datasourceId:grantForm.datasourceId,table:grantForm.table,action:grantForm.action
  });
  else await requestWrite(base+'/column-grants','POST',{
   datasourceId:grantForm.datasourceId,table:grantForm.table,column:grantForm.column,action:grantForm.action
  });
  message.success(scope==='table'?'表级授权已保存':'字段级授权已保存');
  await loadGrants();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
function openCredentials(record:Record<string,unknown>){
 Object.assign(credentialForm,{datasourceId:String(record.id),secretRef:''});
 credentialDialog.value=true;
}
async function saveCredentials(){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/datasources/'+credentialForm.datasourceId+'/read-secret','PUT',
   {secretRef:credentialForm.secretRef});
  credentialDialog.value=false;message.success('只读凭据引用已保存，密码未经过浏览器。');
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}
async function testDatasource(record:Record<string,unknown>){
 saving.value=true;error.value='';
 try{
  const res=await requestWrite('/api/v1/datasources/'+record.id+'/connection-test','POST');
  if((res as unknown as {connected?:boolean}).connected)message.success('TLS 数据源连接测试通过');
  else error.value='未通过连接验证';
 }catch(e){error.value='连接测试未通过，可能缺少目标白名单、服务器端凭据或有效 TLS：'+(e as Error).message;}
 finally{saving.value=false;}
}
async function login(){
 busy.value=true;error.value='';
 try{
  const response=await fetch('/api/v1/auth/login',{method:'POST',credentials:'omit',
    headers:{'Content-Type':'application/json'},body:JSON.stringify(loginForm)});
  if(!response.ok)throw new Error(response.status===401?'用户名或密码不正确（连续错误会暂时锁定账号）':'登录失败 HTTP '+response.status);
  const result=await response.json() as {token:string;mustChangePassword:boolean};
  token.value=result.token;mustChange.value=result.mustChangePassword;
  forcedPasswordChange.value=result.mustChangePassword;
  error.value='';profile.value=null;
  loginForm.password='';
  if(!mustChange.value){await reload();await loadAdmin();}
 }catch(e){error.value=(e as Error).message;}
 finally{busy.value=false;}
}
async function logout(){
 try{if(token.value)await requestWrite('/api/v1/auth/logout','POST');}catch{}
 token.value='';profile.value=null;mustChange.value=false;forcedPasswordChange.value=false;
 changeForm.currentPassword='';changeForm.newPassword='';agents.value=[];datasources.value=[];
 usersList.value=[];rolesList.value=[];agentTokenOnce.value='';
}
function cancelPasswordChange(){
 if(forcedPasswordChange.value)return;
 mustChange.value=false;changeForm.currentPassword='';changeForm.newPassword='';error.value='';
}
async function updatePassword(){
 saving.value=true;error.value='';
 try{
  if(changeForm.newPassword.length<12 || new TextEncoder().encode(changeForm.newPassword).length>72 ||
    !/[\p{Lu}]/u.test(changeForm.newPassword) || !/[\p{Ll}]/u.test(changeForm.newPassword) ||
    !/[0-9]/.test(changeForm.newPassword) || !/[^\p{L}\p{N}]/u.test(changeForm.newPassword))
    throw new Error('新密码需要至少12位，包含大小写字母、数字和特殊字符，且不超过72字节');
  if(changeForm.currentPassword===changeForm.newPassword)throw new Error('新密码不能与当前密码相同');
  const response=await fetch('/api/v1/auth/password',{
   method:'POST',credentials:'omit',
   headers:{Authorization:'Bearer '+token.value,'Content-Type':'application/json'},
   body:JSON.stringify(changeForm)
  });
  if(response.status===400){
   const body=await response.json().catch(()=>({})) as {detail?:string;message?:string};
   const detail=String(body.detail||body.message||'');
   throw new Error(detail.includes('CURRENT_PASSWORD_INCORRECT')?'当前密码不正确，请重试。':'新密码不符合安全要求，或与原密码相同。');
  }
  if(response.status===401)throw new Error('登录会话失效，请退出后重新登录。');
  if(response.status===403)throw new Error('登录会话未获得改密权限，请重新登录。');
  if(!response.ok)throw new Error('修改密码失败：HTTP '+response.status);
  // The server revokes all sessions after successful rotation. Clear the local token.
  await logout();message.success('密码已修改，原会话已失效，请使用新密码重新登录');
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}

</script>
<template>
 <div class="shell">
  <aside class="sidebar">
   <div class="brand"><span class="symbol">◆</span><div>MCP DataGuard<small>AI DATABASE SECURITY</small></div></div>
   <span class="section">工作台</span>
   <button v-for="page in pages" :key="page[0]" :class="['nav',{active:selected===page[0]}]" @click="if(!mustChange){selected=page[0];if(page[0]==='users')loadAdmin();if(page[0]==='rules')loadPolicies();if(page[0]==='audit'){loadAuditEvents();loadOperations();loadSecurityAlerts();loadTrends()};if(page[0]==='flowable'){loadOperations();loadDeadLetters();loadWorkflowDesigns()}}">{{page[1]}}</button>
   <div class="foot">● 开源研发版 v0.5</div>
  </aside>
  <main class="main">
   <header class="bar"><div>工作台 / <strong>{{title}}</strong></div>
    <div class="actions"><a-button size="small" :disabled="!token||mustChange" :loading="busy" @click="reload">刷新</a-button>
     <a-button v-if="token&&!mustChange" size="small" @click="mustChange=true;error=''">修改密码</a-button>
     <a-button v-else size="small" @click="logout">退出</a-button>
    </div></header>
   <div class="content">
    <div class="heading"><div><h1>{{title}}</h1><p>AI Agent 多数据库安全访问与管理平台</p></div><span class="status">开发中 写入禁用</span></div>

    <section v-if="!token" class="panel native-login">
     <h3>MCP DataGuard 登录</h3>
     <p>统一使用内置账号认证，无需 Keycloak。</p>
     <a-form :model="loginForm" layout="vertical" @finish="login">
      <a-form-item label="用户名" name="username"><a-input v-model:value="loginForm.username" autocomplete="username"/></a-form-item>
      <a-form-item label="密码" name="password"><a-input-password v-model:value="loginForm.password" autocomplete="current-password"/></a-form-item>
      <a-button html-type="submit" type="primary" block :loading="busy">登录</a-button>
     </a-form>
    </section>
    <section v-else-if="mustChange" class="panel native-login">
     <h3>修改登录密码</h3>
     
     <a-form :model="changeForm" layout="vertical" @finish="updatePassword">
      <a-form-item label="当前密码"><a-input-password v-model:value="changeForm.currentPassword" autocomplete="current-password"/></a-form-item>
      <a-form-item label="新密码"><a-input-password v-model:value="changeForm.newPassword" autocomplete="new-password"/></a-form-item>
      <p>至少12位，包含大写、小写字母、数字和特殊字符。</p>
      <a-button type="primary" block html-type="submit" :loading="saving">修改密码并重新登录</a-button>
      <a-button v-if="!forcedPasswordChange" block style="margin-top:10px" @click="cancelPasswordChange">取消修改</a-button>
     </a-form>
    </section>
    <a-alert v-if="error" type="warning" show-icon :message="error" class="alert"/>
    <template v-if="profile&&!mustChange">
     <section v-if="selected==='dashboard'">
      <div class="cards">
       <article><small>当前用户</small><strong class="person">{{profile.displayName}}</strong><span>{{profile.username}}</span></article>
       <article><small>Agent 数量（当前页）</small><strong>{{agents.length}}</strong><span>真实授权数据</span></article>
       <article><small>数据源数量（当前页）</small><strong>{{datasources.length}}</strong><span>真实授权数据</span></article>
      </div>
      <div class="panel"><h3>欢迎使用 MCP DataGuard</h3><p>公司内部安全管理平台。SQL 写入能力默认关闭，敏感操作须经独立审批与授权。</p></div>
     </section>
     <section v-else-if="selected==='agents'" class="panel">
      <div class="panel-heading"><h3>Agent 列表</h3><a-button size="small" type="primary" @click="newAgent">新增 Agent</a-button></div>
      <a-table row-key="id" :columns="columnsAgents" :data-source="agents" size="small" :loading="busy" :pagination="{pageSize:10}">
       <template #bodyCell="{column,record}">
        <template v-if="column.key==='actions'">
         <a-button size="small" type="link" @click="editAgent(record)">编辑</a-button>
         <a-button v-if="record.status==='ACTIVE'" size="small" type="link" @click="openGrants(record)">权限配置</a-button>
         <a-button v-if="record.status==='DISABLED'" size="small" type="link" @click="openEnroll(record)">绑定身份</a-button>
         <a-popconfirm v-if="record.status!=='REVOKED'" title="确认永久撤销这个 Agent？" ok-text="撤销" cancel-text="取消" @confirm="revokeAgent(record)">
          <a-button size="small" type="link" danger>撤销</a-button>
         </a-popconfirm>
        </template>
       </template>
      </a-table>
     </section>
     <section v-else-if="selected==='datasources'" class="panel">
      <div class="panel-heading"><h3>数据源列表</h3><a-button size="small" type="primary" @click="newDatasource">新增数据源</a-button></div>
      <a-table row-key="id" :columns="columnsSources" :data-source="datasources" size="small" :loading="busy" :pagination="{pageSize:10}">
       <template #bodyCell="{column,record}">
        <template v-if="column.key==='actions'">
         <a-button size="small" type="link" @click="editDatasource(record)">编辑</a-button>
         <a-button size="small" type="link" @click="openCredentials(record)">凭据引用</a-button>
         <a-button size="small" type="link" @click="openMaskDialog(record)">字段脱敏</a-button>
         <a-button size="small" type="link" @click="testDatasource(record)">测试连接</a-button>
         <a-popconfirm v-if="record.status!=='DISABLED'" title="确认停用数据源？" ok-text="停用" cancel-text="取消" @confirm="disableDatasource(record)">
          <a-button size="small" type="link" danger>停用</a-button>
         </a-popconfirm>
        </template>
       </template>
      </a-table>
     </section>

     <section v-else-if="selected==='flowable'" class="panel">
      <div class="panel-heading"><h3>审批实例状态</h3><a-button size="small" @click="loadOperations();loadWorkflowDesigns()">刷新</a-button></div>
      <div class="panel-heading" style="margin-top:16px"><h3>可视化流程设计</h3>
       <a-button type="primary" @click="workflowDesignTarget=null;workflowDesignOpenReset()">新建两级审批流程</a-button>
      </div>
      <a-table row-key="id" size="small" :data-source="workflowDesigns" :pagination="{pageSize:5}"
       :columns="[{title:'名称',dataIndex:'name'},{title:'编码',dataIndex:'code'},
        {title:'草稿修订',dataIndex:'revision'},{title:'已发布修订',dataIndex:'publishedRevision'},
        {title:'操作',key:'actions'}]">
       <template #bodyCell="{column,record}">
        <template v-if="column.key==='actions'">
         <a-button type="link" size="small" @click="editWorkflowDesign(record)">设计下一修订</a-button>
         <a-popconfirm title="只发布流程元数据，不自动启动流程或执行 SQL。确认？" @confirm="publishWorkflowDesign(record)">
          <a-button type="link" size="small">发布</a-button>
         </a-popconfirm>
        </template>
       </template>
      </a-table>
      
      <a-table style="margin-top:16px" row-key="id" size="small" :data-source="approvalRows"
       :columns="[{title:'审批编号',dataIndex:'id'},{title:'审批状态',dataIndex:'approvalStatus'},
        {title:'执行状态',dataIndex:'executionStatus'},{title:'到期时间',dataIndex:'expiresAt'},{title:'操作',key:'actions'}]">
       <template #bodyCell="{column,record}">
        <template v-if="column.key==='actions' && record.approvalStatus==='PENDING' && record.executionStatus==='NOT_SCHEDULED'">
         <a-button type="link" size="small" @click="approving={id:String(record.id),version:Number(record.version)};approvalDialog=true">审核通过</a-button>
         <a-button danger type="link" size="small" @click="rejectingApproval={id:String(record.id),version:Number(record.version)};rejectionDialog=true">拒绝</a-button>
        </template>
       </template>
      </a-table>
      <h4 style="margin-top:20px">审批失败事件与死信</h4>
      <a-table row-key="id" size="small" :data-source="deadLetters" :pagination="{pageSize:5}"
       :columns="[{title:'事件',dataIndex:'type'},{title:'工单',dataIndex:'approvalId'},
         {title:'重试次数',dataIndex:'attempts'},{title:'操作',key:'actions'}]">
       <template #bodyCell="{column,record}">
        <a-popconfirm v-if="column.key==='actions'" title="确认将此事件重新加入安全审批队列？"
          @confirm="retryDeadLetter(record.id)">
         <a-button type="link" size="small">安全重试</a-button>
        </a-popconfirm>
       </template>
      </a-table>
     </section>
     <section v-else-if="selected==='audit'" class="panel">
      <div class="panel-heading"><h3>管理操作审计</h3><a-button size="small" @click="loadAuditEvents">刷新</a-button></div>
      
      <div v-if="operationalSummary" class="cards" style="margin-top:16px">
       <article><small>被拒绝请求</small><strong>{{operationalSummary.deniedRequests}}</strong></article>
       <article><small>待审批工单</small><strong>{{operationalSummary.pendingApprovals}}</strong></article>
       <article><small>未处理安全告警</small><strong>{{operationalSummary.openAlerts}}</strong></article>
      </div>
      <h4>近 14 天 SQL 请求和拒绝趋势</h4>
      <div style="display:flex;gap:8px;align-items:end;height:165px;margin:16px 0 6px">
       <div v-for="day in dailyTrends" :key="day.day" :title="day.day+' 请求：'+day.requests+'；拒绝：'+day.denied+'；告警：'+day.alerts"
         style="display:flex;flex:1;min-width:0;height:100%;align-items:end;gap:2px">
        <div style="flex:1;background:#0090ff;border-radius:4px 4px 0 0;min-height:2px"
          :style="{height:trendPercent(day.requests)}"></div>
        <div style="flex:1;background:#e5484d;border-radius:4px 4px 0 0;min-height:2px"
          :style="{height:trendPercent(day.denied)}"></div>
       </div>
      </div>
      <div style="display:flex;justify-content:space-between;font-size:12px;color:var(--slate11)">
       <span>{{dailyTrends[0]?.day ?? '无数据'}}</span>
       <span>蓝色：请求 红色：拒绝（每日数量）</span>
       <span>{{dailyTrends[dailyTrends.length-1]?.day ?? ''}}</span>
      </div>
      <h4>SQL 请求安全记录</h4>
      <a-table row-key="id" size="small" :data-source="sqlRequestRows" :pagination="{pageSize:10}"
       :columns="[{title:'请求编号',dataIndex:'id'},{title:'工具',dataIndex:'tool_name'},
        {title:'决策',dataIndex:'decision'},{title:'状态',dataIndex:'request_status'},
        {title:'指纹',dataIndex:'sql_fingerprint'},{title:'接收时间',dataIndex:'receivedAt'}]"/>
      <h4>安全告警处置</h4>
      <a-table row-key="id" size="small" :data-source="securityAlerts" :pagination="{pageSize:10}"
       :columns="[{title:'规则',dataIndex:'rule'},{title:'等级',dataIndex:'severity'},
        {title:'状态',dataIndex:'status'},{title:'发生时间',dataIndex:'observedAt'},
        {title:'操作',key:'actions'}]">
       <template #bodyCell="{column,record}">
        <a-popconfirm v-if="column.key==='actions' && record.status==='OPEN'" title="确认已查看此安全告警？"
         @confirm="acknowledgeAlert(record.id)">
         <a-button size="small" type="link">确认告警</a-button>
        </a-popconfirm>
       </template>
      </a-table>
      <h4>审计链完整性</h4>
      <div class="actions"><a-button size="small" @click="verifyAuditStream('mcp-denied-sql')">核验拒绝请求链</a-button>
      <a-button size="small" @click="verifyAuditStream('mcp-read-sql')">核验只读执行链</a-button></div>
      <a-alert v-if="auditVerification" style="margin-top:8px" :type="auditVerification.startsWith('完整性验证通过')?'success':'error'"
        :message="auditVerification" show-icon/>
      <h4>管理操作历史</h4>
      <a-table style="margin-top:16px" row-key="id" size="small" :data-source="systemEvents"
       :pagination="{pageSize:10}"
       :columns="[{title:'时间',dataIndex:'createdAt'},{title:'操作',dataIndex:'operation'},
        {title:'操作者',dataIndex:'actor'},{title:'资源类型',dataIndex:'resourceType'},
        {title:'资源编号',dataIndex:'resourceId'}]"/>
     </section>
     <section v-else-if="selected==='rules'" class="panel">
      <div class="panel-heading"><h3>SQL 安全策略草稿</h3><div class="actions">
       <a-button size="small" @click="loadPolicies">刷新</a-button>
       <a-button type="primary" size="small" @click="policyDialog=true">创建草稿</a-button>
      </div></div>
      
      <a-card size="small" style="margin:16px 0" title="已发布策略 多规则决策预览">
       <a-space wrap>
        <a-input v-model:value="effectivePolicyScope.table" placeholder="数据库表名" style="width:190px"/>
        <a-select v-model:value="effectivePolicyScope.environment" style="width:145px"
          :options="['DEV','TEST','STAGING','PROD'].map(x=>({label:x,value:x}))"/>
        <a-select v-model:value="effectivePolicyScope.operation" style="width:145px"
          :options="['SELECT','UPDATE','DELETE','INSERT'].map(x=>({label:x,value:x}))"/>
        <a-button :loading="saving" @click="evaluateEffectivePolicies">模拟跨策略优先级</a-button>
       </a-space>
       <a-alert v-if="effectivePolicyResult" style="margin-top:12px" type="info" show-icon
        :message="effectivePolicyResult"/>
      </a-card>
      <a-table row-key="id" size="small" :data-source="sqlPolicies" :pagination="{pageSize:10}"
       :columns="[{title:'编码',dataIndex:'code'},{title:'名称',dataIndex:'name'},{title:'状态',dataIndex:'status'},{title:'操作',key:'actions'}]">
       <template #bodyCell="{column,record}">
        <template v-if="column.key==='actions'">
         <a-button size="small" type="link" @click="openPolicyRules(record)">规则版本</a-button>
         <a-popconfirm v-if="record.status==='DRAFT' && record.latestVersion>0" title="仅允许发布 DENY 规则，不会允许 SQL 执行。继续？"
          @confirm="changePolicyState(record,'publish')">
          <a-button size="small" type="link">发布拒绝规则</a-button>
         </a-popconfirm>
         <a-popconfirm v-if="record.status==='ACTIVE'" title="确认停用这条拒绝策略？"
          @confirm="changePolicyState(record,'disable')">
          <a-button size="small" type="link" danger>停用</a-button>
         </a-popconfirm>
        </template>
       </template>
      </a-table>
     </section>
     <section v-else-if="selected==='users'" class="panel">
      <div class="panel-heading">
       <h3>用户与角色</h3>
       <div class="actions"><a-button size="small" @click="loadAdmin">刷新</a-button>
        <a-button size="small" @click="userDialog=true">创建用户</a-button>
        <a-button size="small" type="primary" @click="roleDialog=true">创建角色</a-button>
       </div>
      </div>
      
      <h4 style="margin-top:24px">内部用户</h4>
      <a-table :data-source="usersList" row-key="id" size="small"
       :columns="[{title:'用户名',dataIndex:'username'},{title:'显示名称',dataIndex:'displayName'},{title:'状态',dataIndex:'status'}]"/>
      <h4>基础角色</h4>
      <a-table :data-source="rolesList" row-key="id" size="small"
       :columns="[{title:'编码',dataIndex:'code'},{title:'名称',dataIndex:'name'},{title:'权限(JSON)',dataIndex:'permissions'}]"/>
      <h4>分配角色（不包括系统角色）</h4>
      <div class="assignment">
       <a-select v-model:value="assignment.userId" placeholder="选择用户" style="min-width:170px">
        <a-select-option v-for="u in usersList" :key="String(u.id)" :value="String(u.id)">{{u.username}}</a-select-option>
       </a-select>
       <a-select v-model:value="assignment.roleId" placeholder="选择非系统角色" style="min-width:170px">
        <a-select-option v-for="r in rolesList.filter(x=>!x.systemRole)" :key="r.id" :value="r.id">{{r.name}}</a-select-option>
       </a-select>
       <a-button type="primary" :disabled="!assignment.userId||!assignment.roleId" @click="assignRole">分配</a-button>
      </div>
     </section>
     <div v-else class="panel placeholder"><h2>{{title}}</h2><p>开发中，暂未提供真实业务功能。</p></div>
    </template>
    <p class="disclaimer">必须通过内置账号或 Agent Token 验证、后端权限校验和隔离的数据库只读账户。当前版本不得连接生产数据库执行 SQL。</p>

   </div>
  </main>

  <a-modal v-model:open="maskDialog" :title="'字段脱敏 '+maskTarget.name" :footer="null" width="700px">
   
   <a-form layout="vertical" style="margin-top:16px">
    <a-form-item label="表名"><a-input v-model:value="maskForm.table" placeholder="orders"/></a-form-item>
    <a-form-item label="字段名"><a-input v-model:value="maskForm.column" placeholder="account_number"/></a-form-item>
    <a-form-item label="脱敏方式"><a-radio-group v-model:value="maskForm.algorithm"><a-radio value="REDACT">REDACT 替换</a-radio><a-radio value="NULLIFY">NULLIFY 置空</a-radio>
     <a-radio value="PARTIAL">PARTIAL 仅尾四位</a-radio>
     <a-radio value="HASH">HASH 租户 HMAC</a-radio></a-radio-group></a-form-item>
    <a-form-item label="适用角色编码"><a-input v-model:value="maskForm.roleCode" placeholder="例如 org-admin；HASH / PARTIAL 必填"/></a-form-item>
    <a-form-item label="优先级"><a-input-number v-model:value="maskForm.priority" :min="1" :max="10000"/></a-form-item>
    <a-button type="primary" :loading="saving" @click="saveMaskRule">保存字段脱敏规则</a-button>
   </a-form>
   <a-table size="small" row-key="id" :data-source="maskRows" :pagination="{pageSize:5}"
    :columns="[{title:'表名',dataIndex:'table'},{title:'字段',dataIndex:'column'},{title:'算法',dataIndex:'algorithm'},{title:'适用角色',dataIndex:'roleCode'},{title:'操作',key:'actions'}]">
    <template #bodyCell="{column,record}"><a-popconfirm v-if="column.key==='actions'" title="确认停用？" @confirm="disableMaskRule(record.id)"><a-button danger type="link" size="small">停用</a-button></a-popconfirm></template>
   </a-table>
  </a-modal>
  <a-modal v-model:open="workflowDesignerOpen" title="通用流程编排 安全审批节点" width="880px" :footer="null">
   
   <a-form layout="vertical" style="margin-top:16px">
    <a-form-item label="流程编码"><a-input v-model:value="workflowDesignForm.code" :disabled="!!workflowDesignTarget" placeholder="sql_review"/></a-form-item>
    <a-form-item label="流程名称"><a-input v-model:value="workflowDesignForm.name"/></a-form-item>
   </a-form>
   <div style="display:flex;align-items:center;gap:8px;overflow-x:auto;margin:16px 0;padding:8px">
    <a-tag color="blue">开始</a-tag>
    <template v-for="(step,index) in workflowSteps" :key="index">
     <span>→</span>
     <div draggable="true" @dragstart="workflowDragging=index" @dragover.prevent @drop.prevent="dropWorkflowStep(index)"
       @dragend="workflowDragging=null" style="border:1px solid #cdced6;border-radius:10px;padding:12px;min-width:170px;cursor:move">
      <strong>{{index+1}}. {{step.label}}</strong>
      <div style="font-size:12px;color:var(--slate11)">{{step.role}}</div>
      <a-tag v-if="workflowHighRiskLast && index===workflowSteps.length-1" color="orange">仅高风险</a-tag>
     </div>
    </template>
    <span>→</span><a-tag color="green">结束</a-tag>
   </div>
   <div v-for="(step,index) in workflowSteps" :key="'input-'+index"
     style="display:flex;gap:8px;align-items:center;margin-bottom:10px">
    <span style="width:55px">{{index+1}} 级</span>
    <a-input v-model:value="step.label" placeholder="节点名称" style="flex:2"/>
    <a-input v-model:value="step.role" placeholder="角色编码" style="flex:1"/>
    <a-button size="small" :disabled="index===0" @click="moveWorkflowStep(index,index-1)">上移</a-button>
    <a-button size="small" :disabled="index===workflowSteps.length-1" @click="moveWorkflowStep(index,index+1)">下移</a-button>
    <a-button danger size="small" :disabled="workflowSteps.length<=2" @click="removeWorkflowStep(index)">删除</a-button>
   </div>
   <a-space wrap style="margin:12px 0">
    <a-button :disabled="workflowSteps.length>=6" @click="addWorkflowStep">增加审批节点</a-button>
    <a-switch v-model:checked="workflowHighRiskLast" :disabled="workflowSteps.length<3"/>
    <span>最后一级仅在 HIGH / CRITICAL 风险时执行</span>
   </a-space>
   <div><a-button type="primary" :loading="saving" @click="saveWorkflowDesign">保存新修订</a-button></div>
  </a-modal>
  <a-modal v-model:open="approvalDialog" title="审核 SQL 变更申请" ok-text="确认审核通过"
   :confirm-loading="saving" @ok="approveTask">
   
   <a-form-item label="审核意见" style="margin-top:14px">
    <a-textarea v-model:value="approvalReason" :maxlength="1000" :rows="3"/>
   </a-form-item>
  </a-modal>
  <a-modal v-model:open="rejectionDialog" title="拒绝审批工单" ok-text="确认拒绝" :confirm-loading="saving" @ok="rejectApproval">
   
   <a-form-item label="拒绝原因" style="margin-top:14px"><a-textarea v-model:value="rejectionReason" :maxlength="1000" :rows="3"/></a-form-item>
  </a-modal>
  <a-modal v-model:open="policyRuleDialog" :title="'规则草稿版本 '+policyTarget.name" :footer="null" width="760px">
   
   <a-form layout="vertical" style="margin-top:16px">
    <a-form-item label="表名"><a-input v-model:value="policyRule.table" placeholder="orders"/></a-form-item>
    <a-form-item label="操作类型">
     <a-select v-model:value="policyRule.operation"
       :options="['SELECT','UPDATE','DELETE','INSERT'].map(x=>({label:x,value:x}))"/>
    </a-form-item>
    <a-form-item label="环境">
     <a-select v-model:value="policyRule.environment">
      <a-select-option value="DEV">DEV</a-select-option><a-select-option value="TEST">TEST</a-select-option>
      <a-select-option value="STAGING">STAGING</a-select-option><a-select-option value="PROD">PROD</a-select-option>
     </a-select>
    </a-form-item>
    <a-form-item label="决策">
     <a-radio-group v-model:value="policyRule.decision"><a-radio value="DENY">拒绝</a-radio>
      <a-radio value="REQUIRE_APPROVAL">需要人工审批</a-radio></a-radio-group>
    </a-form-item>
    <a-form-item label="优先级"><a-input-number v-model:value="policyRule.priority" :min="1" :max="10000"/></a-form-item>
    <a-button v-if="policyTarget.status==='DRAFT'" type="primary" :loading="saving" @click="savePolicyRule">保存新草稿版本</a-button>
    <a-button v-if="policyTarget.status==='DRAFT'" style="margin-left:8px" :loading="saving" @click="simulatePolicyRule">模拟匹配</a-button>
    <a-alert v-if="policySimulation" style="margin-top:12px" type="info" show-icon :message="policySimulation"/>
   </a-form>
   <h4 style="margin-top:18px">已有规则版本</h4>
   <a-table size="small" :data-source="policyVersions" row-key="revision" :pagination="{pageSize:5}"
    :columns="[{title:'版本',dataIndex:'revision'},{title:'优先级',dataIndex:'priority'},
     {title:'决策',dataIndex:'decision'},{title:'条件(JSON)',dataIndex:'rule'},
     {title:'操作',key:'actions'}]">
    <template #bodyCell="{column,record}">
     <a-popconfirm v-if="column.key==='actions' && record.published && record.decision==='DENY' &&
        record.revision<policyTarget.latestVersion && ['ACTIVE','DISABLED'].includes(policyTarget.status)"
       title="确认恢复这个历史 DENY 版本？将产生新的不可变发布版本。"
       @confirm="rollbackPolicy(record.revision)">
      <a-button type="link" size="small">恢复此版本</a-button>
     </a-popconfirm>
    </template>
   </a-table>
  </a-modal>
  <a-modal v-model:open="policyDialog" title="创建 SQL 策略草稿" ok-text="创建"
   :confirm-loading="saving" @ok="savePolicy">
   <a-form layout="vertical">
    <a-form-item label="策略编码"><a-input v-model:value="policyForm.code" placeholder="restrict_reports"/></a-form-item>
    <a-form-item label="策略名称"><a-input v-model:value="policyForm.name" placeholder="报表访问限制"/></a-form-item>
    
   </a-form>
  </a-modal>
  <a-modal v-model:open="userDialog" title="创建内置用户" ok-text="保存"
   :confirm-loading="saving" @ok="saveUser">
   <a-form layout="vertical">

    <a-form-item label="用户名"><a-input v-model:value="userForm.username"/></a-form-item>
    <a-form-item label="显示名称"><a-input v-model:value="userForm.displayName"/></a-form-item>
    <a-form-item label="初始复杂密码"><a-input-password v-model:value="userForm.password" autocomplete="new-password"/></a-form-item>
    
   </a-form>
  </a-modal>
  <a-modal v-model:open="roleDialog" title="创建角色" ok-text="保存"
   :confirm-loading="saving" @ok="saveRole">
   <a-form layout="vertical">
    <a-form-item label="角色编码"><a-input v-model:value="roleForm.code" placeholder="operator_read"/></a-form-item>
    <a-form-item label="角色名称"><a-input v-model:value="roleForm.name"/></a-form-item>
    <a-form-item label="基础管理权限">
     <a-checkbox-group v-model:value="roleForm.permissions">
      <a-checkbox value="agent:read">查看 Agent</a-checkbox>
      <a-checkbox value="agent:write">编辑 Agent</a-checkbox>
      <a-checkbox value="datasource:read">查看数据源</a-checkbox>
      <a-checkbox value="datasource:write">编辑数据源</a-checkbox>
     </a-checkbox-group>
    </a-form-item>
   </a-form>
  </a-modal>
  <a-modal v-model:open="enrollDialog" title="生成 Agent 访问令牌" ok-text="生成或轮换"
   :confirm-loading="saving" @ok="saveEnroll" @cancel="agentTokenOnce=''">
   <p>新令牌仅显示一次，生成新令牌会立即撤销旧令牌。</p>
   <a-textarea v-if="agentTokenOnce" :value="agentTokenOnce" :rows="3" readonly/>
   
  </a-modal>
  <a-modal v-model:open="grantDialog" :title="'Agent 权限配置 '+grantAgent.name" :footer="null" width="720px">
   
   <a-form layout="vertical" style="margin-top:18px">
    <a-form-item label="目标数据源">
     <a-select v-model:value="grantForm.datasourceId" placeholder="选择 ACTIVE 数据源" @change="loadGrants">
      <a-select-option v-for="d in datasources.filter(x=>x.status==='ACTIVE')" :key="String(d.id)" :value="String(d.id)">{{d.name}} {{d.environment}}</a-select-option>
     </a-select>
    </a-form-item>
    <a-form-item label="表名"><a-input v-model:value="grantForm.table" placeholder="例如 orders（小写字母、数字、下划线）"/></a-form-item>
    <a-form-item label="字段名"><a-input v-model:value="grantForm.column" placeholder="例如 order_id（仅字段授权时必填）"/></a-form-item>
    <a-form-item label="授权决策">
     <a-radio-group v-model:value="grantForm.action">
      <a-radio value="ALLOW">ALLOW 允许</a-radio><a-radio value="DENY">DENY 拒绝</a-radio>
     </a-radio-group>
    </a-form-item>
    <div class="actions">
     <a-button :disabled="!grantForm.datasourceId" :loading="saving" @click="saveGrant('table')">保存表级 Schema 权限</a-button>
     <a-button type="primary" :disabled="!grantForm.datasourceId" :loading="saving" @click="saveGrant('column')">保存字段级 SELECT 权限</a-button>
    </div>
   </a-form>
   <h4 style="margin-top:24px">现有表级授权</h4>
   <a-table size="small" :loading="grantLoading" :pagination="{pageSize:5}" :data-source="schemaGrants"
    :columns="[{title:'表名',dataIndex:'table'},{title:'决策',dataIndex:'action'}]"/>
   <h4>现有字段级授权</h4>
   <a-table size="small" :loading="grantLoading" :pagination="{pageSize:5}" :data-source="columnGrants"
    :columns="[{title:'表名',dataIndex:'table'},{title:'字段',dataIndex:'column'},{title:'决策',dataIndex:'action'}]"/>
  </a-modal>
  <a-modal v-model:open="credentialDialog" title="配置服务端只读凭据引用" ok-text="保存"
   :confirm-loading="saving" @ok="saveCredentials">
   <a-form layout="vertical">
    <a-form-item label="环境变量前缀"><a-input v-model:value="credentialForm.secretRef"
      placeholder="DG_TARGET_REPORTING_READ"/></a-form-item>
    
   </a-form>
  </a-modal>
  <a-modal v-model:open="agentDialog" :title="agentForm.id?'编辑 Agent':'新增 Agent'"
   :confirm-loading="saving" ok-text="保存" @ok="saveAgent">
   <a-form layout="vertical">
    <a-form-item label="唯一编码"><a-input v-model:value="agentForm.code" :disabled="!!agentForm.id" placeholder="agent_sales" maxlength="80"/></a-form-item>
    <a-form-item label="Agent 名称"><a-input v-model:value="agentForm.name" maxlength="200"/></a-form-item>
    <a-form-item label="说明"><a-textarea v-model:value="agentForm.description" :rows="2"/></a-form-item>
    <div class="form-pair">
     <a-form-item label="每分钟调用上限"><a-input-number v-model:value="agentForm.rateLimitPerMinute" :min="1" :max="100000"/></a-form-item>
     <a-form-item label="并发上限"><a-input-number v-model:value="agentForm.concurrencyLimit" :min="1" :max="1000"/></a-form-item>
     <a-form-item label="最大返回行数"><a-input-number v-model:value="agentForm.maxRows" :min="1" :max="100000"/></a-form-item>
     <a-form-item label="最大执行毫秒"><a-input-number v-model:value="agentForm.maxExecutionMs" :min="100" :max="3600000"/></a-form-item>
    </div>
    
   </a-form>
  </a-modal>
  <a-modal v-model:open="sourceDialog" :title="sourceForm.id?'编辑数据源':'新增数据源'"
   :confirm-loading="saving" ok-text="保存" @ok="saveDatasource">
   <a-form layout="vertical">
    <a-form-item label="唯一编码"><a-input v-model:value="sourceForm.code" :disabled="!!sourceForm.id" placeholder="pg_reporting"/></a-form-item>
    <a-form-item label="数据源名称"><a-input v-model:value="sourceForm.name" maxlength="200"/></a-form-item>
    <div class="form-pair">
     <a-form-item label="数据库类型">
      <a-select v-model:value="sourceForm.dbType" :disabled="!!sourceForm.id">
       <a-select-option value="POSTGRESQL">PostgreSQL</a-select-option>
       <a-select-option value="MYSQL">MySQL</a-select-option>
      </a-select>
     </a-form-item>
     <a-form-item label="环境">
      <a-select v-model:value="sourceForm.environment" :disabled="!!sourceForm.id">
       <a-select-option value="DEV">开发</a-select-option><a-select-option value="TEST">测试</a-select-option>
       <a-select-option value="STAGING">预发布</a-select-option><a-select-option value="PROD">生产</a-select-option>
      </a-select>
     </a-form-item>
    </div>
    <a-form-item label="服务器地址"><a-input v-model:value="sourceForm.host" placeholder="db.internal"/></a-form-item>
    <div class="form-pair">
     <a-form-item label="端口"><a-input-number v-model:value="sourceForm.port" :min="1" :max="65535"/></a-form-item>
     <a-form-item label="数据库名"><a-input v-model:value="sourceForm.databaseName" maxlength="255"/></a-form-item>
    </div>
    
   </a-form>
  </a-modal>
 </div>
</template>
<style scoped>
/* Radix Colors: Slate light + Blue light + Amber/Red/Green semantic scales */
.shell{--slate1:#fcfcfd;--slate2:#f9f9fb;--slate3:#f0f0f3;--slate4:#e8e8ec;--slate5:#e0e1e6;--slate6:#d9d9e0;--slate7:#cdced6;--slate8:#b9bbc6;--slate9:#8b8d98;--slate10:#80838d;--slate11:#60646c;--slate12:#1c2024;--blue3:#edf6ff;--blue4:#d6ebff;--blue7:#8ec8f6;--blue9:#0090ff;--blue10:#0588f0;--blue11:#0d74ce;--blue12:#113264;--amber3:#fff7c2;--amber7:#e9c162;--amber11:#946800;--red9:#e5484d;--green9:#30a46c;--panel-radius:8px;min-height:100vh;display:flex;background:var(--slate2);color:var(--slate12);font:14px/1.5 system-ui,"Microsoft YaHei",sans-serif}
*{box-sizing:border-box}.sidebar{width:224px;flex:none;background:var(--slate1);border-right:0.5px solid var(--slate6);padding:20px 12px;display:flex;flex-direction:column}
.brand{display:flex;align-items:center;gap:10px;font-weight:650;font-size:14px;padding:4px 10px 24px}.brand small{display:block;font-size:12px;color:var(--slate11);letter-spacing:0;margin-top:1px;font-weight:400}.symbol{display:grid;place-items:center;background:var(--blue3);color:var(--blue11);width:32px;height:32px;border-radius:6px}
.section{font-size:12px;color:var(--slate11);margin:4px 10px 10px}.nav{width:100%;border:0;display:block;text-align:left;border-radius:6px;background:transparent;color:var(--slate11);padding:10px 12px;margin:1px 0;cursor:pointer;font-size:14px}.nav:hover{background:var(--slate3);color:var(--slate12)}.nav.active{background:var(--blue3);color:var(--blue11);font-weight:600}
.foot{margin-top:auto;border-top:0.5px solid var(--slate6);padding:16px 10px;color:var(--slate11);font-size:12px}.main{min-width:0;flex:1}.bar{height:60px;background:var(--slate1);border-bottom:0.5px solid var(--slate6);display:flex;align-items:center;justify-content:space-between;padding:0 24px;font-size:12px;color:var(--slate11)}.bar strong{color:var(--slate12);margin-left:8px}.actions{display:flex;gap:8px;align-items:center}
.content{padding:24px;max-width:1280px;margin:0 auto}.heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;gap:16px}.heading h1{font-size:20px;line-height:28px;margin:0;font-weight:650}.heading p{font-size:12px;color:var(--slate11);margin:4px 0 0}.status{font-size:12px;color:var(--amber11);background:var(--amber3);border:0.5px solid var(--amber7);border-radius:6px;padding:6px 10px;white-space:nowrap}
.alert{margin:12px 0}.cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-bottom:16px}.cards article{background:var(--slate1);border:0.5px solid var(--slate6);border-radius:var(--panel-radius);padding:18px;display:flex;flex-direction:column;gap:8px}.cards small,.cards span{color:var(--slate11);font-size:12px}.cards strong,.cards strong.person{font-size:20px;font-weight:650;line-height:28px}.panel{background:var(--slate1);border:0.5px solid var(--slate6);border-radius:var(--panel-radius);padding:20px;margin-bottom:16px}.panel h3{font-size:14px;line-height:22px;font-weight:650;margin:0 0 16px}.panel h4{font-size:14px;font-weight:600}.assignment{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.panel-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;gap:12px}.panel-heading h3{margin:0}.form-pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}.form-pair :deep(.ant-form-item){min-width:0}.form-pair :deep(.ant-input-number),.form-pair :deep(.ant-select){width:100%}.panel p{font-size:14px;line-height:1.6;color:var(--slate11)}.native-login{max-width:480px;margin:32px auto;padding:28px}.native-login h3{font-size:20px}.placeholder{text-align:center;padding:44px 24px}.disclaimer{font-size:12px;color:var(--slate11);margin-top:18px}
.shell :deep(.ant-btn-primary){background:var(--blue9);box-shadow:none}.shell :deep(.ant-btn-primary:hover){background:var(--blue10)}.shell :deep(.ant-btn-link),.shell :deep(.ant-typography a){color:var(--blue11)}.shell :deep(.ant-table-wrapper .ant-table),.shell :deep(.ant-form),.shell :deep(.ant-input),.shell :deep(.ant-select),.shell :deep(.ant-alert){font-size:14px}.shell :deep(.ant-card),.shell :deep(.ant-input),.shell :deep(.ant-input-affix-wrapper),.shell :deep(.ant-select-selector),.shell :deep(.ant-modal-content){border-radius:6px;box-shadow:none}.shell :deep(.ant-card),.shell :deep(.ant-input),.shell :deep(.ant-input-affix-wrapper),.shell :deep(.ant-select-selector){border-width:0.5px;border-color:var(--slate7)}.shell :deep(.ant-table-thead>tr>th){background:var(--slate2);color:var(--slate11);font-size:12px;font-weight:600;border-bottom:0.5px solid var(--slate6)}.shell :deep(.ant-table-tbody>tr>td){border-bottom:0.5px solid var(--slate5)}.shell :deep(.ant-alert){border-width:0.5px;border-radius:6px}.shell :deep(.ant-form-item-label>label){font-size:14px;color:var(--slate12)}.shell :deep(.ant-btn){box-shadow:none;transition:background-color 250ms ease-out,color 250ms ease-out,border-color 250ms ease-out}
@media(max-width:850px){.sidebar{width:180px}.content{padding:18px}.cards{grid-template-columns:1fr}.bar{padding:0 16px}}
@media(max-width:570px){.sidebar{width:62px;padding:16px 6px}.brand div,.section,.nav{font-size:0}.brand{padding:0 5px 22px}.nav:after{content:"•";font-size:14px}.foot{display:none}}
</style>
