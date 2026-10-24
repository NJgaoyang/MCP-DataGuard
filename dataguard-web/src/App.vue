<script setup lang="ts">
import {ref,computed,reactive,onMounted,onUnmounted} from 'vue';
import {message} from 'ant-design-vue';
import UiIcon from './components/UiIcon.vue';
const pages=[
 ['dashboard','总览仪表盘'],['agents','Agent 管理'],['datasources','数据源管理'],
 ['rules','SQL 安全规则'],['flowable','Flowable 审批'],['audit','SQL 审计与监控'],
 ['users','用户与权限']
];
const selected=ref('dashboard');
const userSearch=ref('');
const userStatusFilter=ref('ALL');
const sqlPolicies=ref<{id:string;code:string;name:string;status:string;version:number;latestVersion:number}[]>([]);
const policyDialog=ref(false);
const operationalSummary=ref<{deniedRequests:number;pendingApprovals:number;openAlerts:number}|null>(null);
type LiveSummary={windowSeconds:number;sampledAt:string;requests:number;qps:number;failureRate:number;p95ReadLatencyMs:number;pendingApprovals:number;openAlerts:number};
const liveSummary=ref<LiveSummary|null>(null);
async function loadLiveSummary(){
 try{liveSummary.value=await getData<LiveSummary>('/api/v1/operations/live');}
 catch(e){liveSummary.value=null;error.value='查询近实时监控失败：'+(e as Error).message;}
}
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
const securityAlertFilter=reactive({status:'',severity:'',rule:''});
async function loadSecurityAlerts(){
 try{const params=new URLSearchParams({status:securityAlertFilter.status,severity:securityAlertFilter.severity,rule:securityAlertFilter.rule,limit:'100'});securityAlerts.value=await getData<typeof securityAlerts.value>('/api/v1/security-alerts?'+params.toString());}
 catch(e){error.value='查询告警失败：'+(e as Error).message;}
}
// Poll only when the authenticated audit workspace is visible.
let liveRefreshTimer:ReturnType<typeof setInterval>|undefined;
onMounted(()=>{
 liveRefreshTimer=setInterval(()=>{
  if(token.value&&!mustChange.value&&selected.value==='audit'&&
    document.visibilityState==='visible'){
   void loadLiveSummary();
   void loadSecurityAlerts();
  }
 },30000);
});
onUnmounted(()=>{if(liveRefreshTimer!==undefined)clearInterval(liveRefreshTimer);});
async function acknowledgeAlert(id:string){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/security-alerts/'+encodeURIComponent(id)+'/acknowledge','POST');
  message.success('告警已确认');await loadSecurityAlerts();await loadOperations();
 }catch(e){error.value=(e as Error).message;}
 finally{saving.value=false;}
}

async function resolveAlert(id:string){
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/security-alerts/'+encodeURIComponent(id)+'/resolve','POST');
  message.success('告警已解决');await loadSecurityAlerts();await loadOperations();
 }catch(e){error.value=(e as Error).message;}finally{saving.value=false;}
}
async function cancelPendingApproval(row:{id:string;version:number}){
 const reason='管理员在控制台取消未执行的审批申请';
 saving.value=true;error.value='';
 try{
  await requestWrite('/api/v1/approvals/'+encodeURIComponent(row.id)+'/cancel',
   'POST',{reason,expectedVersion:Number(row.version)});
  message.success('申请已取消，执行保持禁止');await loadOperations();
 }catch(e){error.value=(e as Error).message;}finally{saving.value=false;}
}
const approvalHistoryVisible=ref(false);
const approvalHistoryId=ref('');
const approvalHistoryRows=ref<{action:string;comment:string;reviewerId:string;actedAt:string}[]>([]);
async function showApprovalHistory(id:string){
 approvalHistoryId.value=id;approvalHistoryRows.value=[];error.value='';
 try{
  approvalHistoryRows.value=await getData<typeof approvalHistoryRows.value>(
    '/api/v1/approvals/'+encodeURIComponent(id)+'/actions');
  approvalHistoryVisible.value=true;
 }catch(e){error.value='读取审批历史失败：'+(e as Error).message;}
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
  effectivePolicyResult.value='最终决策：'+localizedStatus(v.decision)+'；匹配 '+v.evaluatedRuleCount+' 条已发布规则。'+
    v.matchedRules.map(x=>x.code+'（优先级 '+x.priority+'，'+localizedStatus(x.decision)+'）').join(' → ');
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
const filteredUsers=computed(()=>usersList.value.filter(u=>
 String(u.username??'').toLowerCase().includes(userSearch.value.trim().toLowerCase()) &&
 (userStatusFilter.value==='ALL'||String(u.status)===userStatusFilter.value)));
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
const statusLabels:Record<string,string>={ACTIVE:'启用',DISABLED:'停用',REVOKED:'已撤销',DRAFT:'草稿',OPEN:'待处理',PENDING:'待审核',CLOSED:'已关闭',ACKNOWLEDGED:'已确认',APPROVED:'已通过',REJECTED:'已拒绝',LOCKED:'已锁定',HIGH:'高风险',CRITICAL:'严重风险',LOW:'低风险',MEDIUM:'中风险',INFO:'提示',DENY:'拒绝',ALLOW:'允许',REQUIRE_APPROVAL:'需要审批',FAILED:'失败',SUCCESS:'成功',COMPLETED:'已完成',NOT_SCHEDULED:'尚未调度',SCHEDULED:'已调度',RUNNING:'运行中',EXECUTED:'已执行',CANCELLED:'已取消',TIMEOUT:'已超时',BLOCKED:'已拦截'};
function localizedStatus(value:unknown){const status=String(value??'');return statusLabels[status]||status||'—';}
function localizedDisplayName(value:unknown){const name=String(value??'');return ({'Test Administrator':'测试管理员','Organization administrators':'组织管理员'} as Record<string,string>)[name]||name;}
const permissionNameMap:Record<string,string>={'agent:read':'查看 Agent','agent:write':'管理 Agent','datasource:read':'查看数据源','datasource:write':'管理数据源','datasource:test':'测试数据源','datasource:secret:write':'管理数据源凭据','identity:admin':'身份与用户管理','agent:identity:write':'管理 Agent 身份','agent:permission:write':'管理 Agent 权限','sql:read':'查看 SQL','sql:write':'SQL 策略管理','approval:read':'查看审批','approval:write':'处理审批','audit:read':'查看审计','security:alert:read':'查看告警'};
function localizedPermissions(value:unknown):string{
 let items:unknown=value;
 if(typeof items==='string'){try{items=JSON.parse(items);}catch{return String(items);}}
 if(!Array.isArray(items))return String(items??'—');
 return items.map(item=>permissionNameMap[String(item)]||String(item)).join('、')||'无';
}
/* Localized presentation: preserve raw event data and permissions. */
const workspaceTab=ref<'users'|'roles'>('users');
const operationLabels:Record<string,string>={BOOTSTRAP_ORGANIZATION:'初始化组织',CREATE_USER:'创建用户',CREATE_ROLE:'创建角色',ASSIGN_ROLE:'分配角色',LOGIN:'用户登录',LOGOUT:'退出登录',PASSWORD_CHANGE:'修改密码',PASSWORD_RESET:'重置密码',UPDATE_USER:'更新用户',DISABLE_USER:'停用用户',ENABLE_USER:'启用用户',CREATE_AGENT:'创建 Agent',REGISTER_AGENT:'注册 Agent',REVOKE_AGENT:'撤销 Agent',CREATE_DATASOURCE:'添加数据源',UPDATE_DATASOURCE:'更新数据源',CREATE_POLICY:'创建安全策略',PUBLISH_POLICY:'发布安全策略',ACKNOWLEDGE_ALERT:'确认安全告警'};
const resourceLabels:Record<string,string>={ORGANIZATION:'组织',USER:'用户',ROLE:'角色',AGENT:'Agent',DATASOURCE:'数据源',SQL_POLICY:'SQL 安全策略',APPROVAL:'审批工单',SECURITY_ALERT:'安全告警',WORKFLOW:'工作流'};
function localizedOperation(value:unknown){const raw=String(value??'');return operationLabels[raw]||(raw?'其他管理操作':'—');}
function localizedResource(value:unknown){const raw=String(value??'');return resourceLabels[raw]||(raw?'其他资源':'—');}
function displayTime(value:unknown){const raw=String(value??'');if(!raw)return '—';const d=new Date(raw);return Number.isNaN(d.getTime())?raw:d.toLocaleString('zh-CN',{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});}
function shortenedIdentifier(value:unknown){const v=String(value??'');return v.length>23?v.slice(0,12)+'…'+v.slice(-6):v||'—';}
const policyCount=computed(()=>sqlPolicies.value.length);
const publishedPolicyCount=computed(()=>sqlPolicies.value.filter(x=>x.status==='ACTIVE').length);
const accountMenuOpen=ref(false);
const dayGreeting=computed(()=>{
 const hour=new Date().getHours();
 return hour<11?'上午好':hour<14?'中午好':hour<19?'下午好':'晚上好';
});
const latestAlerts=computed(()=>[...securityAlerts.value].sort((a,b)=>
 new Date(b.observedAt).getTime()-new Date(a.observedAt).getTime()).slice(0,3));
function displayAlertTime(value:string){
 const date=new Date(value);
 return Number.isNaN(date.getTime())?value:date.toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'});
}
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
  if(!mustChange.value){await reload();await Promise.allSettled([loadOperations(),loadSecurityAlerts()]);await loadAdmin();}
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
 <div class="shell" :class="{'auth-shell':!token||mustChange,'compact-page':!!token&&!mustChange&&selected!=='dashboard'}">
  <aside v-if="token&&!mustChange" class="sidebar">
   <div class="brand"><img class="brand-mark" src="/dataguard-mark.svg" alt="" width="42" height="46"/><div class="brand-copy"><strong class="brand-wordmark">Data<span>Guard</span></strong><small>数据守卫</small></div></div>
   <span class="section">工作台</span>
   <button v-for="page in pages" :key="page[0]" :class="['nav',{active:selected===page[0]}]" @click="if(!mustChange){selected=page[0];if(page[0]==='dashboard'){loadOperations();loadSecurityAlerts()};if(page[0]==='users')loadAdmin();if(page[0]==='rules')loadPolicies();if(page[0]==='audit'){loadAuditEvents();loadOperations();loadSecurityAlerts();loadTrends();loadLiveSummary()};if(page[0]==='flowable'){loadOperations();loadDeadLetters();loadWorkflowDesigns()}}"><UiIcon :name="page[0]" :size="19"/><span>{{page[1]}}</span></button>
  </aside>
  <main class="main">
   <header v-if="token&&!mustChange" class="bar"><div class="breadcrumb">工作台 <span>/</span> <strong>{{title}}</strong></div>
    <div class="actions">
     <button type="button" class="alerts-trigger" aria-label="查看安全告警" title="查看安全告警" @click="selected='audit';loadOperations();loadSecurityAlerts()"><UiIcon name="bell" :size="19"/><i v-if="(operationalSummary?.openAlerts??0)>0" class="notification-dot"></i></button>
     <span class="account-separator" aria-hidden="true"></span>
     <template v-if="token">
      <a-popover v-model:open="accountMenuOpen" placement="bottomRight" trigger="click" overlay-class-name="dg-account-popover" :arrow="false">
       <template #content>
        <div class="account-detail">
          <div class="account-summary">
           <span class="account-summary-avatar">{{(profile?.displayName || profile?.username || '用').slice(0,1)}}</span>
           <span class="account-summary-info"><strong>{{profile?.displayName || profile?.username || '当前用户'}}</strong><small>账号：{{profile?.username || '未获取'}}</small></span>
          </div>
          <div class="account-menu-divider"></div>
          <button type="button" class="account-menu-item" @click="accountMenuOpen=false;mustChange=true;error=''"><span>修改密码</span><span class="menu-item-arrow">›</span></button>
          <button type="button" class="account-menu-item account-menu-danger" @click="accountMenuOpen=false;logout()"><span>退出登录</span><span class="menu-item-arrow">›</span></button>
         </div>
       </template>
       <button class="account-trigger" type="button" aria-label="查看当前登录用户">
        <span class="account-avatar">{{(profile?.displayName || profile?.username || '用').slice(0,1)}}</span>
        <span class="account-name">{{profile?.username || '当前用户'}}</span>
        <UiIcon name="chevron" :size="14" class="account-chevron"/>
       </button>
      </a-popover>
     </template>
    </div></header>
   <div class="content">
    

    <section v-if="!token" class="auth-layout">
     <div class="auth-brand-panel">
      <div class="auth-brand-top"><img class="auth-brand-mark" src="/dataguard-mark.svg" alt="" width="58" height="63"/><span class="auth-brand-copy"><strong class="brand-wordmark">Data<span>Guard</span></strong><small>数据守卫</small></span></div>
      <div class="auth-brand-body">
       <div class="auth-eyebrow">DATA ACCESS GOVERNANCE</div>
       <h1>让每一次数据访问<br>安全、可控、可追溯</h1>
       <p>面向 Agent 与企业数据源的统一安全访问管理平台</p>
       <div class="auth-capabilities">
        <div><span class="capability-icon">01</span><div><strong>访问权限治理</strong><small>Agent 身份与数据源访问控制</small></div></div>
        <div><span class="capability-icon">02</span><div><strong>SQL 安全防护</strong><small>安全规则校验与执行审计</small></div></div>
        <div><span class="capability-icon">03</span><div><strong>变更审批管控</strong><small>高风险操作审批与全链路追踪</small></div></div>
       </div>
      </div>
      <div class="auth-brand-foot">DataGuard · 数据守卫</div>
     </div>
     <div class="auth-form-panel">
      <div class="auth-login-card">
       <div class="auth-mobile-brand"><img class="auth-brand-mark" src="/dataguard-mark.svg" alt="" width="44" height="48"/><span class="auth-brand-copy"><strong class="brand-wordmark">Data<span>Guard</span></strong><small>数据守卫</small></span></div>
       <div class="auth-login-heading"><h2>登录管理平台</h2><p>请输入管理员分配的账号和密码</p></div>
       <a-form :model="loginForm" layout="vertical" @finish="login" class="auth-form">
        <a-form-item label="账号" name="username" :rules="[{required:true,message:'请输入账号'}]"><a-input v-model:value="loginForm.username" autocomplete="username" placeholder="请输入账号" size="large"/></a-form-item>
        <a-form-item label="密码" name="password" :rules="[{required:true,message:'请输入密码'}]"><a-input-password v-model:value="loginForm.password" autocomplete="current-password" placeholder="请输入密码" size="large"/></a-form-item>
        <a-button html-type="submit" type="primary" block size="large" :loading="busy" class="auth-submit">登录</a-button>
       </a-form>
       <p class="auth-support">如需开通账号或重置密码，请联系系统管理员</p>
      </div>
      <div class="auth-copyright">© 2026 DataGuard</div>
     </div>
    </section>
    <section v-else-if="mustChange" class="auth-layout">
     <div class="auth-brand-panel">
      <div class="auth-brand-top"><img class="auth-brand-mark" src="/dataguard-mark.svg" alt="" width="58" height="63"/><span class="auth-brand-copy"><strong class="brand-wordmark">Data<span>Guard</span></strong><small>数据守卫</small></span></div>
      <div class="auth-brand-body"><div class="auth-eyebrow">ACCOUNT SECURITY</div><h1>安全始于身份认证</h1><p>保护企业数据资产，建立可信访问边界。</p></div>
      <div class="auth-brand-foot">DataGuard · 数据守卫</div>
     </div>
     <div class="auth-form-panel">
      <div class="auth-login-card">
       <div class="auth-mobile-brand"><img class="auth-brand-mark" src="/dataguard-mark.svg" alt="" width="44" height="48"/><span class="auth-brand-copy"><strong class="brand-wordmark">Data<span>Guard</span></strong><small>数据守卫</small></span></div>
       <div class="auth-login-heading"><h2>{{forcedPasswordChange?'首次登录，请修改密码':'修改登录密码'}}</h2><p>新密码至少 12 位，包含大小写字母、数字及特殊字符</p></div>
       <a-form :model="changeForm" layout="vertical" @finish="updatePassword" class="auth-form">
        <a-form-item label="当前密码" name="currentPassword" :rules="[{required:true,message:'请输入当前密码'}]"><a-input-password v-model:value="changeForm.currentPassword" autocomplete="current-password" size="large"/></a-form-item>
        <a-form-item label="新密码" name="newPassword" :rules="[{required:true,message:'请输入新密码'}]"><a-input-password v-model:value="changeForm.newPassword" autocomplete="new-password" size="large"/></a-form-item>
        <a-button type="primary" block size="large" html-type="submit" :loading="saving" class="auth-submit">确认修改并重新登录</a-button>
        <a-button v-if="!forcedPasswordChange" block size="large" class="auth-cancel" @click="cancelPasswordChange">返回工作台</a-button>
       </a-form>
      </div>
      <div class="auth-copyright">© 2026 DataGuard</div>
     </div>
    </section>
    <a-alert v-if="error" type="warning" show-icon :message="error" class="alert"/>
    <template v-if="profile&&!mustChange">
     <section v-if="selected==='dashboard'" class="dashboard-screen">
      <div class="dashboard-hero">
       <h1>{{dayGreeting}}，{{profile?.username || '管理员'}}</h1>
       <p>实时掌握数据库安全态势，保障企业数据安全</p>
      </div>
      <div class="overview">
       <div class="overview-main">
        <div class="overview-stats">
         <button type="button" class="metric" @click="selected='agents'">
          <span class="metric-symbol metric-agent"><UiIcon name="agents" :size="29"/></span>
          <span class="metric-copy"><small>Agent 数量</small><strong>{{agents.length}}</strong></span><span class="metric-token blue">A</span>
         </button>
         <button type="button" class="metric" @click="selected='datasources'">
          <span class="metric-symbol metric-db"><UiIcon name="datasources" :size="28"/></span>
          <span class="metric-copy"><small>数据源</small><strong>{{datasources.length}}</strong></span><span class="metric-token cyan">D</span>
         </button>
         <button type="button" class="metric" @click="selected='flowable';loadOperations()">
          <span class="metric-symbol metric-approval"><UiIcon name="document" :size="28"/></span>
          <span class="metric-copy"><small>待审批</small><strong>{{operationalSummary?.pendingApprovals ?? 0}}</strong></span><span class="metric-token red">!</span>
         </button>
        </div>
        <section class="panel overview-block business-panel">
         <div class="overview-section-head"><h2>业务管理</h2><p>快速进入核心功能，管理数据库安全策略与资源</p></div>
         <div class="overview-shortcuts">
          <button type="button" class="shortcut shortcut-agent" @click="selected='agents';newAgent()">
           <span class="shortcut-icon"><UiIcon name="agents" :size="24"/></span><UiIcon class="shortcut-watermark" name="agents" :size="102"/>
           <strong>Agent 管理</strong><span class="shortcut-caption">新建 Agent、管理运行状态</span><span class="shortcut-arrow"><UiIcon name="arrow" :size="18"/></span>
          </button>
          <button type="button" class="shortcut shortcut-db" @click="selected='datasources';newDatasource()">
           <span class="shortcut-icon"><UiIcon name="datasources" :size="24"/></span><UiIcon class="shortcut-watermark" name="datasources" :size="102"/>
           <strong>数据源管理</strong><span class="shortcut-caption">新增数据源、配置连接信息</span><span class="shortcut-arrow"><UiIcon name="arrow" :size="18"/></span>
          </button>
          <button type="button" class="shortcut shortcut-rules" @click="selected='rules';loadPolicies()">
           <span class="shortcut-icon"><UiIcon name="rules" :size="24"/></span><UiIcon class="shortcut-watermark" name="rules" :size="102"/>
           <strong>SQL 安全规则</strong><span class="shortcut-caption">查看规则、配置安全策略</span><span class="shortcut-arrow"><UiIcon name="arrow" :size="18"/></span>
          </button>
         </div>
        </section>
        <section class="panel overview-block activity-panel">
         <div class="overview-section-head overview-head-actions"><div><h2>近期 SQL 活动</h2><p>SQL 请求与审批概览</p></div><button type="button" class="overview-text-link" @click="selected='audit';loadAuditEvents();loadTrends()"><span>查看审计</span><UiIcon name="arrow" :size="16"/></button></div>
         <div class="overview-figures">
          <div class="figure"><span class="figure-icon blue"><UiIcon name="document" :size="23"/></span><span class="figure-content"><small>拒绝请求</small><strong>{{operationalSummary?.deniedRequests ?? 0}}</strong></span></div>
          <div class="figure"><span class="figure-icon amber"><UiIcon name="clock" :size="23"/></span><span class="figure-content"><small>待处理审批</small><strong>{{operationalSummary?.pendingApprovals ?? 0}}</strong></span></div>
          <div class="figure"><span class="figure-icon rose"><UiIcon name="rules" :size="23"/></span><span class="figure-content"><small>未处理告警</small><strong>{{operationalSummary?.openAlerts ?? 0}}</strong></span></div>
         </div>
         <div v-if="!sqlRequestRows.length" class="activity-empty">
          <span class="activity-empty-icon"><UiIcon name="document" :size="42"/><UiIcon name="search" :size="27"/></span>
          <strong>暂无 SQL 活动数据</strong><span>当有 SQL 请求或审批操作后，将在此展示记录</span>
         </div>
         <div v-else class="activity-preview">
          <span>已获取 {{sqlRequestRows.length}} 条 SQL 请求记录</span><button type="button" @click="selected='audit';loadOperations();loadAuditEvents()">查看最近请求 <UiIcon name="arrow" :size="15"/></button>
         </div>
        </section>
       </div>
       <aside class="overview-aside">
        <section class="panel overview-block security-panel">
         <div class="overview-section-head overview-head-actions"><h2>安全状态</h2><button type="button" class="overview-text-link" @click="selected='audit';loadOperations();loadSecurityAlerts()"><span>查看详情</span><UiIcon name="arrow" :size="16"/></button></div>
         <div class="security-overview"><div class="security-ring"><span>{{operationalSummary?.openAlerts ?? 0}}</span><small>未处理告警</small></div></div>
         <div class="security-legend"><span><i class="legend-dot blue"></i>SQL 拒绝 {{operationalSummary?.deniedRequests ?? 0}}</span><span><i class="legend-dot red"></i>待处理告警 {{operationalSummary?.openAlerts ?? 0}}</span></div>
        </section>
        <section class="panel overview-block risk-panel">
         <div class="overview-section-head overview-head-actions"><h2>风险动态</h2><button type="button" class="overview-text-link" @click="selected='audit';loadOperations();loadSecurityAlerts()"><span>查看全部</span><UiIcon name="arrow" :size="16"/></button></div>
         <div v-if="latestAlerts.length" class="risk-items">
          <button type="button" class="risk-item" v-for="alert in latestAlerts" :key="alert.id" @click="selected='audit';loadSecurityAlerts()">
           <span class="risk-item-icon"><UiIcon name="warning" :size="19"/></span>
           <span class="risk-item-body"><strong>{{alert.rule || '安全告警'}}</strong><small>{{alert.severity}} · {{alert.status}}</small></span>
           <time>{{displayAlertTime(alert.observedAt)}}</time>
          </button>
         </div>
         <div v-else class="risk-empty"><span class="risk-clear-icon"><UiIcon name="shield-check" :size="24"/></span><strong>暂无风险动态</strong><span>新的安全告警将在此显示</span></div>
        </section>
        <section class="panel overview-block todo-panel">
         <div class="overview-section-head overview-head-actions"><h2>待办事项</h2><button type="button" class="overview-text-link" @click="selected='flowable';loadOperations()"><span>查看全部</span><UiIcon name="arrow" :size="16"/></button></div>
         <button type="button" class="todo-row" @click="selected='flowable';loadOperations()"><span class="todo-tag">审批</span><span>待审批申请</span><strong>{{operationalSummary?.pendingApprovals ?? 0}}</strong><UiIcon name="arrow" :size="15"/></button>
         <button type="button" class="todo-row" @click="selected='audit';loadOperations();loadSecurityAlerts()"><span class="todo-tag alert-tag">告警</span><span>待处理安全告警</span><strong>{{operationalSummary?.openAlerts ?? 0}}</strong><UiIcon name="arrow" :size="15"/></button>
        </section>
       </aside>
      </div>
     </section>
     <section v-else-if="selected==='agents'" class="panel data-panel">
      <div class="panel-heading"><h3>Agent 列表 <span class="table-count">{{agents.length}}</span></h3><a-button type="primary" @click="newAgent">新增 Agent</a-button></div>
      <div v-if="!busy && !agents.length" class="empty-state"><div class="empty-icon"><UiIcon name="agents" :size="25"/></div><strong>暂无 Agent</strong><span class="empty-description">点击右上角“新增 Agent”，开始创建并管理 Agent</span></div>
      <a-table v-if="busy || agents.length" row-key="id" :columns="columnsAgents" :data-source="agents" size="small" :loading="busy" :pagination="{pageSize:10}">
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
     <section v-else-if="selected==='datasources'" class="panel data-panel">
      <div class="panel-heading"><h3>数据源列表 <span class="table-count">{{datasources.length}}</span></h3><a-button type="primary" @click="newDatasource">新增数据源</a-button></div>
      <div v-if="!busy && !datasources.length" class="empty-state"><div class="empty-icon"><UiIcon name="datasources" :size="25"/></div><strong>暂无数据源</strong><span class="empty-description">点击右上角“新增数据源”，配置数据库连接</span></div>
      <a-table v-if="busy || datasources.length" row-key="id" :columns="columnsSources" :data-source="datasources" size="small" :loading="busy" :pagination="{pageSize:10}">
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

     <section v-else-if="selected==='flowable'" class="panel flowable-panel">
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
        <template v-if="column.key==='actions'">
         <a-button type="link" size="small" @click="showApprovalHistory(String(record.id))">审批历史</a-button>
         <template v-if="record.approvalStatus==='PENDING' && record.executionStatus==='NOT_SCHEDULED'">
         <a-button type="link" size="small" @click="approving={id:String(record.id),version:Number(record.version)};approvalDialog=true">审核通过</a-button>
         <a-button danger type="link" size="small" @click="rejectingApproval={id:String(record.id),version:Number(record.version)};rejectionDialog=true">拒绝</a-button>
         <a-popconfirm title="确认取消此待审批申请？操作会留存审计记录且无法恢复。" ok-text="确认取消" cancel-text="返回" @confirm="cancelPendingApproval(record)"><a-button type="link" size="small">取消申请</a-button></a-popconfirm>
         </template>
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
     <section v-else-if="selected==='audit'" class="audit-workspace b2b-workspace">
 <header class="workspace-heading"><div><h2>SQL 审计与监控</h2><p>集中查看安全态势、请求记录及操作历史</p></div>
  <a-button @click="loadAuditEvents();loadOperations();loadSecurityAlerts();loadTrends();loadLiveSummary()">刷新数据</a-button>
 </header>
 <div v-if="operationalSummary" class="audit-summary">
  <div class="audit-kpi"><span class="audit-kpi-icon audit-blue"><UiIcon name="document" :size="19"/></span><span><small>被拒绝请求</small><strong>{{operationalSummary.deniedRequests}}</strong></span></div>
  <div class="audit-kpi"><span class="audit-kpi-icon audit-amber"><UiIcon name="clock" :size="19"/></span><span><small>待审批工单</small><strong>{{operationalSummary.pendingApprovals}}</strong></span></div>
  <div class="audit-kpi"><span class="audit-kpi-icon audit-rose"><UiIcon name="warning" :size="19"/></span><span><small>未处理安全告警</small><strong>{{operationalSummary.openAlerts}}</strong></span></div>
 </div>
 <section v-if="liveSummary" class="workspace-surface">
  <div class="workspace-section-title"><div><h3>近五分钟安全指标</h3><p>服务端汇总指标 · 点击“刷新数据”获取最新值</p></div><span class="workspace-count">5 分钟滚动窗口</span></div>
  <div class="live-metrics">
   <div><small>请求总数</small><strong>{{liveSummary.requests}}</strong></div>
   <div><small>每秒请求量</small><strong>{{liveSummary.qps}}</strong></div>
   <div><small>请求失败率</small><strong>{{(liveSummary.failureRate*100).toFixed(1)}}%</strong></div>
   <div><small>只读执行 P95</small><strong>{{liveSummary.p95ReadLatencyMs}} <span>ms</span></strong></div>
  </div>
 </section>
 <section class="workspace-surface">
  <div class="workspace-section-title"><div><h3>近 14 天 SQL 请求趋势</h3><p>蓝色表示请求次数，红色表示拒绝次数</p></div></div>
  <div v-if="dailyTrends.length" class="trend-plot">
   <div v-for="day in dailyTrends" :key="day.day" class="trend-bar" :title="day.day+' 请求 '+day.requests+' 次；拒绝 '+day.denied+' 次'">
    <div class="trend-bars"><i class="trend-request" :style="{height:trendPercent(day.requests)}"></i><i class="trend-denied" :style="{height:trendPercent(day.denied)}"></i></div><span>{{day.day.slice(5)}}</span>
   </div>
  </div>
  <div v-else class="workspace-empty compact-empty"><UiIcon name="audit" :size="25"/><span>暂无趋势数据，产生 SQL 请求后将在这里展示</span></div>
 </section>
 <section class="workspace-surface">
  <div class="workspace-section-title"><div><h3>SQL 请求安全记录</h3><p>每次请求的决策与执行状态</p></div><span class="workspace-count">{{sqlRequestRows.length}} 条</span></div>
  <a-table row-key="id" size="small" :data-source="sqlRequestRows" :pagination="{pageSize:10}" :scroll="{x:850}"
   :columns="[{title:'请求编号',dataIndex:'id'},{title:'工具',dataIndex:'tool_name'},{title:'决策',dataIndex:'decision'},{title:'状态',dataIndex:'request_status'},{title:'SQL 指纹',dataIndex:'sql_fingerprint'},{title:'接收时间',dataIndex:'receivedAt'}]">
   <template #bodyCell="{column,record}">
    <template v-if="column.dataIndex==='decision'||column.dataIndex==='request_status'">{{localizedStatus(record[column.dataIndex])}}</template>
    <template v-else-if="column.dataIndex==='receivedAt'">{{displayTime(record.receivedAt)}}</template>
    <template v-else-if="column.dataIndex==='id'||column.dataIndex==='sql_fingerprint'"><span :title="String(record[column.dataIndex]??'')">{{shortenedIdentifier(record[column.dataIndex])}}</span></template>
   </template>
  </a-table>
 </section>
 <section class="workspace-surface">
  <div class="workspace-section-title"><div><h3>安全告警处置</h3><p>核查异常事件并保留处置记录</p></div><span class="workspace-count">{{securityAlerts.length}} 条</span></div>
  <div class="security-alert-filters">
   <a-select v-model:value="securityAlertFilter.status" style="width:135px" :options="[{value:'',label:'全部状态'},{value:'OPEN',label:'待处理'},{value:'ACKNOWLEDGED',label:'已确认'},{value:'RESOLVED',label:'已解决'}]" @change="loadSecurityAlerts"/>
   <a-select v-model:value="securityAlertFilter.severity" style="width:135px" :options="[{value:'',label:'全部等级'},{value:'LOW',label:'低'},{value:'MEDIUM',label:'中'},{value:'HIGH',label:'高'},{value:'CRITICAL',label:'严重'}]" @change="loadSecurityAlerts"/>
   <a-input v-model:value="securityAlertFilter.rule" placeholder="规则编码（大写）" style="max-width:200px" @pressEnter="loadSecurityAlerts"/>
   <a-button @click="loadSecurityAlerts">筛选</a-button>
  </div>
  <a-table row-key="id" size="small" :data-source="securityAlerts" :pagination="{pageSize:10}" :scroll="{x:660}"
   :columns="[{title:'安全规则',dataIndex:'rule'},{title:'风险等级',dataIndex:'severity'},{title:'处理状态',dataIndex:'status'},{title:'发生时间',dataIndex:'observedAt'},{title:'操作',key:'actions'}]">
   <template #bodyCell="{column,record}">
    <template v-if="column.key==='actions'"><a-popconfirm v-if="record.status==='OPEN'" title="确认已查看这条安全告警？" ok-text="确认" cancel-text="返回" @confirm="acknowledgeAlert(record.id)"><a-button size="small" type="link">确认告警</a-button></a-popconfirm><a-popconfirm v-else-if="record.status==='ACKNOWLEDGED'" title="确认这条告警已排除？将保留处理审计。" ok-text="解决" cancel-text="返回" @confirm="resolveAlert(record.id)"><a-button type="link" size="small">解决告警</a-button></a-popconfirm><span v-else class="muted-cell">—</span></template>
    <template v-else-if="column.dataIndex==='severity'||column.dataIndex==='status'">{{localizedStatus(record[column.dataIndex])}}</template>
    <template v-else-if="column.dataIndex==='observedAt'">{{displayTime(record.observedAt)}}</template>
   </template>
  </a-table>
 </section>
 <section class="workspace-surface">
  <div class="workspace-section-title"><div><h3>管理操作历史</h3><p>中文摘要，悬停可查看原始代码与完整编号</p></div><span class="workspace-count">{{systemEvents.length}} 条</span></div>
  <a-table row-key="id" size="small" :data-source="systemEvents" :pagination="{pageSize:10}" :scroll="{x:860}"
   :columns="[{title:'操作时间',dataIndex:'createdAt'},{title:'操作内容',dataIndex:'operation'},{title:'操作账号',dataIndex:'actor'},{title:'资源类型',dataIndex:'resourceType'},{title:'资源编号',dataIndex:'resourceId'}]">
   <template #bodyCell="{column,record}">
    <template v-if="column.dataIndex==='createdAt'">{{displayTime(record.createdAt)}}</template>
    <template v-else-if="column.dataIndex==='operation'"><span :title="String(record.operation)">{{localizedOperation(record.operation)}}</span></template>
    <template v-else-if="column.dataIndex==='resourceType'"><span :title="String(record.resourceType??'')">{{localizedResource(record.resourceType)}}</span></template>
    <template v-else-if="column.dataIndex==='actor'||column.dataIndex==='resourceId'"><span :title="String(record[column.dataIndex]??'')">{{shortenedIdentifier(record[column.dataIndex])}}</span></template>
   </template>
  </a-table>
 </section>
 <section class="workspace-surface">
  <div class="workspace-section-title"><div><h3>审计链完整性校验</h3><p>核验拒绝记录与只读执行记录</p></div></div>
  <div class="integrity-actions"><a-button @click="verifyAuditStream('mcp-denied-sql')">核验拒绝请求链</a-button><a-button @click="verifyAuditStream('mcp-read-sql')">核验只读执行链</a-button></div>
  <a-alert v-if="auditVerification" style="margin-top:12px" :type="auditVerification.startsWith('完整性验证通过')?'success':'error'" :message="auditVerification" show-icon/>
 </section>
</section>
      <section v-else-if="selected==='rules'" class="rules-workspace b2b-workspace">
 <header class="workspace-heading">
  <div><h2>SQL 安全规则</h2><p>集中管理策略草稿、发布状态，并模拟多规则优先级决策</p></div>
  <div class="workspace-heading-actions"><a-button @click="loadPolicies">刷新列表</a-button><a-button type="primary" @click="policyDialog=true">创建策略草稿</a-button></div>
 </header>
 <div class="rule-metrics">
  <div><span>策略总数</span><strong>{{policyCount}}</strong></div>
  <div><span>已发布</span><strong>{{publishedPolicyCount}}</strong></div>
  <div><span>草稿及其他状态</span><strong>{{policyCount-publishedPolicyCount}}</strong></div>
 </div>
 <section class="workspace-surface simulator-panel">
  <div class="workspace-section-title"><div><h3>决策模拟</h3><p>预览指定表、环境和操作的规则决策，不会实际执行 SQL</p></div></div>
  <div class="simulator-controls">
   <label><span>数据库表名</span><a-input v-model:value="effectivePolicyScope.table" placeholder="例如 orders"/></label>
   <label><span>运行环境</span><a-select v-model:value="effectivePolicyScope.environment" :options="[{label:'开发环境',value:'DEV'},{label:'测试环境',value:'TEST'},{label:'预发布环境',value:'STAGING'},{label:'生产环境',value:'PROD'}]"/></label>
   <label><span>SQL 操作</span><a-select v-model:value="effectivePolicyScope.operation" :options="['SELECT','UPDATE','DELETE','INSERT'].map(x=>({label:x,value:x}))"/></label>
   <a-button type="primary" :loading="saving" @click="evaluateEffectivePolicies">模拟决策</a-button>
  </div>
  <a-alert v-if="effectivePolicyResult" style="margin-top:12px" type="info" show-icon :message="effectivePolicyResult"/>
 </section>
 <section class="workspace-surface policies-panel">
  <div class="workspace-section-title"><div><h3>策略列表</h3><p>管理规则版本，已发布策略支持停用</p></div><span class="workspace-count">共 {{sqlPolicies.length}} 条</span></div>
  <a-table row-key="id" size="small" :data-source="sqlPolicies" :pagination="{pageSize:10}" :scroll="{x:670}"
   :columns="[{title:'策略编码',dataIndex:'code'},{title:'策略名称',dataIndex:'name'},{title:'发布状态',dataIndex:'status'},{title:'操作',key:'actions'}]">
   <template #bodyCell="{column,record}">
    <template v-if="column.dataIndex==='status'"><span class="status-label" :class="{'is-active':record.status==='ACTIVE'}">{{localizedStatus(record.status)}}</span></template>
    <template v-else-if="column.key==='actions'">
     <div class="table-actions">
      <a-button type="link" size="small" @click="openPolicyRules(record)">规则版本</a-button>
      <a-popconfirm v-if="record.status==='DRAFT'&&record.latestVersion>0" title="仅发布拒绝规则，不授予 SQL 执行权限。确认发布？" ok-text="发布" cancel-text="返回" @confirm="changePolicyState(record,'publish')"><a-button type="link" size="small">发布</a-button></a-popconfirm>
      <a-popconfirm v-if="record.status==='ACTIVE'" title="确认停用该 SQL 策略？" ok-text="停用" cancel-text="返回" @confirm="changePolicyState(record,'disable')"><a-button type="link" danger size="small">停用</a-button></a-popconfirm>
     </div>
    </template>
   </template>
  </a-table>
 </section>
</section>
      <section v-else-if="selected==='users'" class="identity-workspace b2b-workspace">
 <header class="workspace-heading">
  <div><h2>用户与权限</h2><p>统一维护登录用户、角色与授权关系</p></div>
  <div class="workspace-heading-actions">
   <a-button v-if="workspaceTab==='users'" type="primary" @click="userDialog=true">新增用户</a-button>
   <a-button v-else type="primary" @click="roleDialog=true">新增角色</a-button>
  </div>
 </header>
 <div class="identity-stats">
  <div><small>用户总数</small><strong>{{usersList.length}}</strong></div>
  <div><small>已启用用户</small><strong>{{usersList.filter(x=>x.status==='ACTIVE').length}}</strong></div>
  <div><small>角色总数</small><strong>{{rolesList.length}}</strong></div>
 </div>
 <section class="workspace-surface identity-content">
  <div class="identity-tabs" role="tablist" aria-label="用户与角色">
   <button type="button" role="tab" :aria-selected="workspaceTab==='users'" :class="{active:workspaceTab==='users'}" @click="workspaceTab='users'">用户管理 <span>{{usersList.length}}</span></button>
   <button type="button" role="tab" :aria-selected="workspaceTab==='roles'" :class="{active:workspaceTab==='roles'}" @click="workspaceTab='roles'">角色管理 <span>{{rolesList.length}}</span></button>
  </div>
  <template v-if="workspaceTab==='users'">
   <div class="identity-toolbar">
    <a-input v-model:value="userSearch" placeholder="搜索用户名" allow-clear class="identity-search"/>
    <a-select v-model:value="userStatusFilter" class="identity-status-select" :options="[{label:'全部状态',value:'ALL'},{label:'已启用',value:'ACTIVE'},{label:'已停用',value:'DISABLED'}]"/>
    <a-button @click="loadAdmin">刷新</a-button>
    <a-button @click="userSearch='';userStatusFilter='ALL'">重置筛选</a-button>
   </div>
   <a-table :data-source="filteredUsers" row-key="id" size="small" :scroll="{x:640}" :pagination="{pageSize:10,showSizeChanger:true,pageSizeOptions:['10','20','50'],showTotal:(total:number)=>'共 '+total+' 条'}"
    :columns="[{title:'用户名',dataIndex:'username'},{title:'显示名称',dataIndex:'displayName'},{title:'账号状态',dataIndex:'status'}]">
    <template #bodyCell="{column,record}">
     <template v-if="column.dataIndex==='displayName'">{{localizedDisplayName(record.displayName)}}</template>
     <template v-else-if="column.dataIndex==='status'"><span class="status-label" :class="{'is-active':record.status==='ACTIVE'}">{{localizedStatus(record.status)}}</span></template>
    </template>
   </a-table>
  </template>
  <template v-else>
   <div class="identity-section-description">角色用于组合授权项，下方展示中文权限说明，内部权限编码保持不变。</div>
   <a-table :data-source="rolesList" row-key="id" size="small" :pagination="{pageSize:10}" :scroll="{x:720}"
    :columns="[{title:'角色编码',dataIndex:'code',width:150},{title:'角色名称',dataIndex:'name',width:170},{title:'授权权限',dataIndex:'permissions'}]">
    <template #bodyCell="{column,record}">
     <template v-if="column.dataIndex==='name'">{{localizedDisplayName(record.name)}}</template>
     <template v-else-if="column.dataIndex==='permissions'"><span class="permission-summary" :title="localizedPermissions(record.permissions)">{{localizedPermissions(record.permissions)}}</span></template>
    </template>
   </a-table>
   <div class="role-assignment-panel">
    <div><h3>分配用户角色</h3><p>选择用户和自定义角色，提交后生效</p></div>
    <div class="role-assignment-controls">
     <a-select v-model:value="assignment.userId" placeholder="选择用户">
      <a-select-option v-for="u in usersList" :key="String(u.id)" :value="String(u.id)">{{u.username}}</a-select-option>
     </a-select>
     <a-select v-model:value="assignment.roleId" placeholder="选择角色">
      <a-select-option v-for="r in rolesList.filter(x=>!x.systemRole)" :key="r.id" :value="r.id">{{localizedDisplayName(r.name)}}</a-select-option>
     </a-select>
     <a-button type="primary" :disabled="!assignment.userId||!assignment.roleId" @click="assignRole">确认分配</a-button>
    </div>
   </div>
  </template>
 </section>
</section>
     <div v-else class="panel placeholder"><h2>{{title}}</h2><p>开发中，暂未提供真实业务功能。</p></div>
    </template>
    

   </div>
  </main>

  <a-modal cancel-text="返回" v-model:open="maskDialog" :title="'字段脱敏 '+maskTarget.name" :footer="null" width="700px">
   
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
  <a-modal cancel-text="返回" v-model:open="workflowDesignerOpen" title="通用流程编排 安全审批节点" width="880px" :footer="null">
   
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
  <a-modal cancel-text="返回" v-model:open="approvalDialog" title="审核 SQL 变更申请" ok-text="确认审核通过"
   :confirm-loading="saving" @ok="approveTask">
   
   <a-form-item label="审核意见" style="margin-top:14px">
    <a-textarea v-model:value="approvalReason" :maxlength="1000" :rows="3"/>
   </a-form-item>
  </a-modal>
  <a-modal cancel-text="返回" v-model:open="rejectionDialog" title="拒绝审批工单" ok-text="确认拒绝" :confirm-loading="saving" @ok="rejectApproval">
   
   <a-form-item label="拒绝原因" style="margin-top:14px"><a-textarea v-model:value="rejectionReason" :maxlength="1000" :rows="3"/></a-form-item>
  </a-modal>
  <a-modal cancel-text="返回" v-model:open="policyRuleDialog" :title="'规则草稿版本 '+policyTarget.name" :footer="null" width="760px">
   
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
  <a-modal cancel-text="返回" v-model:open="policyDialog" title="创建 SQL 策略草稿" ok-text="创建"
   :confirm-loading="saving" @ok="savePolicy">
   <a-form layout="vertical">
    <a-form-item label="策略编码"><a-input v-model:value="policyForm.code" placeholder="restrict_reports"/></a-form-item>
    <a-form-item label="策略名称"><a-input v-model:value="policyForm.name" placeholder="报表访问限制"/></a-form-item>
    
   </a-form>
  </a-modal>
  <a-modal v-model:open="approvalHistoryVisible" title="审批操作历史" :footer="null" width="760px">
   <p class="muted-cell" style="margin-bottom:12px">工单编号：{{approvalHistoryId}}</p>
   <a-table row-key="actedAt" size="small" :pagination="{pageSize:8}" :scroll="{x:600}" :data-source="approvalHistoryRows"
    :columns="[{title:'操作',dataIndex:'action'},{title:'审批人',dataIndex:'reviewerId'},{title:'意见',dataIndex:'comment'},{title:'时间',dataIndex:'actedAt'}]">
    <template #bodyCell="{column,record}">
     <span v-if="column.dataIndex==='actedAt'">{{displayTime(record.actedAt)}}</span>
     <span v-else-if="column.dataIndex==='reviewerId'" :title="record.reviewerId">{{shortenedIdentifier(record.reviewerId)}}</span>
     <span v-else-if="column.dataIndex==='action'">{{localizedStatus(record.action)}}</span>
    </template>
   </a-table>
  </a-modal>
  <a-modal cancel-text="返回" v-model:open="userDialog" title="创建内置用户" ok-text="保存"
   :confirm-loading="saving" @ok="saveUser">
   <a-form layout="vertical">

    <a-form-item label="用户名"><a-input v-model:value="userForm.username"/></a-form-item>
    <a-form-item label="显示名称"><a-input v-model:value="userForm.displayName"/></a-form-item>
    <a-form-item label="初始复杂密码"><a-input-password v-model:value="userForm.password" autocomplete="new-password"/></a-form-item>
    
   </a-form>
  </a-modal>
  <a-modal cancel-text="返回" v-model:open="roleDialog" title="创建角色" ok-text="保存"
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
  <a-modal cancel-text="返回" v-model:open="enrollDialog" title="生成 Agent 访问令牌" ok-text="生成或轮换"
   :confirm-loading="saving" @ok="saveEnroll" @cancel="agentTokenOnce=''">
   <p>新令牌仅显示一次，生成新令牌会立即撤销旧令牌。</p>
   <a-textarea v-if="agentTokenOnce" :value="agentTokenOnce" :rows="3" readonly/>
   
  </a-modal>
  <a-modal cancel-text="返回" v-model:open="grantDialog" :title="'Agent 权限配置 '+grantAgent.name" :footer="null" width="720px">
   
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
  <a-modal cancel-text="返回" v-model:open="credentialDialog" title="配置服务端只读凭据引用" ok-text="保存"
   :confirm-loading="saving" @ok="saveCredentials">
   <a-form layout="vertical">
    <a-form-item label="环境变量前缀"><a-input v-model:value="credentialForm.secretRef"
      placeholder="DG_TARGET_REPORTING_READ"/></a-form-item>
    
   </a-form>
  </a-modal>
  <a-modal cancel-text="返回" v-model:open="agentDialog" :title="agentForm.id?'编辑 Agent':'新增 Agent'"
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
  <a-modal cancel-text="返回" v-model:open="sourceDialog" :title="sourceForm.id?'编辑数据源':'新增数据源'"
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
*{box-sizing:border-box}.sidebar{width:208px;flex:none;background:var(--slate1);border-right:0.5px solid var(--slate6);padding:20px 12px;display:flex;flex-direction:column}
.brand{display:flex;align-items:center;gap:10px;font-weight:650;font-size:14px;padding:4px 10px 24px}.brand small{display:block;font-size:12px;color:var(--slate11);letter-spacing:0;margin-top:1px;font-weight:400}.symbol{display:grid;place-items:center;background:var(--blue3);color:var(--blue11);width:32px;height:32px;border-radius:6px}
.section{font-size:12px;color:var(--slate11);margin:4px 10px 10px}.nav{width:100%;border:0;display:block;text-align:left;border-radius:6px;background:transparent;color:var(--slate11);padding:10px 12px;margin:1px 0;cursor:pointer;font-size:14px}.nav:hover{background:var(--slate3);color:var(--slate12)}.nav.active{background:var(--blue3);color:var(--blue11);font-weight:600}
.foot{margin-top:auto;border-top:0.5px solid var(--slate6);padding:16px 10px;color:var(--slate11);font-size:12px}.main{min-width:0;flex:1}.bar{height:60px;background:var(--slate1);border-bottom:0.5px solid var(--slate6);display:flex;align-items:center;justify-content:space-between;padding:0 24px;font-size:12px;color:var(--slate11)}.bar strong{color:var(--slate12);margin-left:8px}.actions{display:flex;gap:8px;align-items:center}
.content{padding:28px 24px;max-width:1040px;margin:0 auto}.heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;gap:12px;min-height:32px}.heading h1{font-size:20px;line-height:28px;margin:0;font-weight:600;letter-spacing:-.3px}.heading p{font-size:12px;color:var(--slate11);margin:4px 0 0}.status{font-size:12px;color:var(--slate11);background:var(--slate3);border:0.5px solid var(--slate6);border-radius:4px;padding:4px 8px;white-space:nowrap}
.alert{margin:12px 0}.cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-bottom:16px}.cards article{background:var(--slate1);border:0.5px solid var(--slate6);border-radius:var(--panel-radius);padding:16px;display:flex;flex-direction:column;gap:8px}.cards small,.cards span{color:var(--slate11);font-size:12px}.cards strong,.cards strong.person{font-size:20px;font-weight:650;line-height:28px}.panel{background:var(--slate1);border:0.5px solid var(--slate6);border-radius:var(--panel-radius);padding:18px;margin-bottom:16px}.panel h3{font-size:14px;line-height:22px;font-weight:650;margin:0 0 16px}.panel h4{font-size:14px;font-weight:600}.assignment{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.panel-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;gap:12px}.panel-heading h3{margin:0}.form-pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}.form-pair :deep(.ant-form-item){min-width:0}.form-pair :deep(.ant-input-number),.form-pair :deep(.ant-select){width:100%}.panel p{font-size:14px;line-height:1.6;color:var(--slate11)}.native-login{max-width:480px;margin:32px auto;padding:28px}.native-login h3{font-size:20px}.placeholder{text-align:center;padding:44px 24px}.disclaimer{font-size:12px;color:var(--slate11);margin-top:18px}
.shell :deep(.ant-btn-primary){background:var(--blue9);box-shadow:none}.shell :deep(.ant-btn-primary:hover){background:var(--blue10)}.shell :deep(.ant-btn-link),.shell :deep(.ant-typography a){color:var(--blue11)}.shell :deep(.ant-table-wrapper .ant-table),.shell :deep(.ant-form),.shell :deep(.ant-input),.shell :deep(.ant-select),.shell :deep(.ant-alert){font-size:14px}.shell :deep(.ant-card),.shell :deep(.ant-input),.shell :deep(.ant-input-affix-wrapper),.shell :deep(.ant-select-selector),.shell :deep(.ant-modal-content){border-radius:6px;box-shadow:none}.shell :deep(.ant-card),.shell :deep(.ant-input),.shell :deep(.ant-input-affix-wrapper),.shell :deep(.ant-select-selector){border-width:0.5px;border-color:var(--slate7)}.shell :deep(.ant-table-thead>tr>th){background:var(--slate2);color:var(--slate11);font-size:12px;font-weight:600;border-bottom:0.5px solid var(--slate6)}.shell :deep(.ant-table-tbody>tr>td){border-bottom:0.5px solid var(--slate5)}.shell :deep(.ant-alert){border-width:0.5px;border-radius:6px}.shell :deep(.ant-form-item-label>label){font-size:14px;color:var(--slate12)}.shell :deep(.ant-btn){box-shadow:none;transition:background-color 250ms ease-out,color 250ms ease-out,border-color 250ms ease-out}
@media(max-width:850px){.sidebar{width:180px}.content{padding:18px}.cards{grid-template-columns:1fr}.bar{padding:0 16px}}
@media(max-width:570px){.sidebar{width:62px;padding:16px 6px}.brand div,.section,.nav{font-size:0}.brand{padding:0 5px 22px}.nav:after{content:"•";font-size:14px}.foot{display:none}}
/* Restrained desktop layout and intentional empty states */
.shell{background:var(--slate2)}
.sidebar{width:216px;padding:22px 12px;background:var(--slate1)}
.brand{gap:10px;padding:4px 8px 26px;line-height:1.25}
.brand small{font-size:12px;color:var(--slate11);margin-top:5px}
.symbol{background:var(--slate12);color:var(--slate1);width:34px;height:34px;border-radius:8px;font-size:15px;font-weight:700}
.section{margin:6px 10px 8px;font-size:12px}
.nav{padding:10px 12px;margin:2px 0;font-weight:450}
.nav.active{background:var(--blue3);color:var(--blue11);font-weight:650}
.foot{color:var(--slate10);padding:12px 10px}
.bar{height:56px;padding:0 28px}
.breadcrumb{display:flex;align-items:center;gap:10px;color:var(--slate11)}
.breadcrumb span{color:var(--slate8)}
.breadcrumb strong{margin:0;color:var(--slate12)}
.content{max-width:1100px;padding:30px 28px 48px}
.heading{margin-bottom:22px}
.heading h1{font-size:20px;font-weight:650}
.status{display:flex;align-items:center;gap:7px;background:var(--slate2);border:0.5px solid var(--slate6);color:var(--slate11);padding:5px 9px}
.status-dot{height:6px;width:6px;border-radius:50%;background:var(--slate9)}
.cards{gap:14px;margin-bottom:20px}
.cards article{min-height:125px;padding:20px;gap:8px;border-radius:8px;background:var(--slate1)}
.cards small{font-size:14px;color:var(--slate11)}
.cards strong,.cards strong.person{font-size:20px;font-weight:650}
.cards span{font-size:12px;color:var(--slate10)}
.panel{padding:22px;border-radius:8px}
.panel-heading{min-height:34px;margin-bottom:16px}
.panel-heading h3{font-size:14px}
.quick-start{padding:22px}
.quick-actions{display:flex;gap:10px;flex-wrap:wrap}
.quick-actions :deep(.ant-btn){height:36px;padding-inline:16px}
.data-panel{padding:22px}
.table-count{display:inline-flex;align-items:center;justify-content:center;height:20px;min-width:20px;padding:0 6px;margin-left:6px;border-radius:4px;background:var(--slate3);color:var(--slate11);font-weight:500;font-size:12px}
.empty-state{min-height:245px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;border-top:0.5px solid var(--slate5)}
.empty-state strong{font-size:14px;font-weight:600}
.empty-icon{display:grid;place-items:center;width:44px;height:44px;border:0.5px solid var(--slate7);border-radius:10px;color:var(--slate10);font-size:20px;font-weight:400}
.shell :deep(.ant-table){background:transparent}
.shell :deep(.ant-table-thead>tr>th){border-radius:0!important}
.shell :deep(.ant-btn-default){border-width:0.5px}
@media(max-width:850px){.sidebar{width:180px}.content{padding:22px 18px}.cards{grid-template-columns:repeat(3,minmax(0,1fr))}}
@media(max-width:650px){.cards{grid-template-columns:1fr}.content{padding:18px 14px}.sidebar{width:62px}}

/* Reference-inspired enterprise layout using Radix Slate and Blue scales. */
.shell{
 --slate1:#fcfcfd;--slate2:#f9f9fb;--slate3:#f0f0f3;--slate4:#e8e8ec;
 --slate5:#e0e1e6;--slate6:#d9d9e0;--slate7:#cdced6;--slate8:#b9bbc6;
 --slate9:#8b8d98;--slate10:#80838d;--slate11:#60646c;--slate12:#1c2024;
 --blue3:#edf6ff;--blue4:#d6ebff;--blue9:#0090ff;--blue10:#0588f0;
 --blue11:#0d74ce;--blue12:#113264;--panel-radius:7px;
 background:var(--slate3);color:var(--slate12);min-height:100vh;
}
.sidebar{width:192px;flex:none;background:#fff;border-right:0.5px solid var(--slate6);padding:22px 10px}
.brand{padding:2px 10px 30px;gap:9px;color:var(--slate12);font-size:14px;font-weight:650;line-height:1.35}
.brand small{margin-top:3px;font-size:12px;font-weight:400;color:var(--slate11)}
.symbol{width:32px;height:32px;background:var(--blue3);border:0.5px solid var(--blue4);border-radius:6px;color:var(--blue11);font-size:14px}
.section{color:var(--slate10);font-size:12px;padding:0 12px;margin:0 0 10px}
.nav{color:var(--slate11);font-size:14px;border-radius:4px;padding:10px 12px;margin:2px 0}
.nav:hover{color:var(--blue11);background:var(--slate2)}
.nav.active{color:var(--blue11);background:var(--blue3);font-weight:600}
.nav.active:before{content:none}.foot{border-top:0.5px solid var(--slate5);color:var(--slate10);padding:14px 12px}
.main{min-width:0;background:var(--slate3)}
.bar{height:56px;background:#fff;border-bottom:0.5px solid var(--slate6);padding:0 26px}
.breadcrumb{color:var(--slate11);display:flex;gap:10px;align-items:center}
.breadcrumb span{color:var(--slate8)}.breadcrumb strong{margin:0;color:var(--slate12)}
.content{max-width:1340px;padding:22px 26px 48px;margin:0 auto}
.heading{margin-bottom:18px;min-height:36px}
.heading h1{font-size:20px;font-weight:650;letter-spacing:-.25px}
.status{border:0.5px solid var(--slate6);background:var(--slate2);color:var(--slate11);border-radius:4px;padding:5px 9px}
.status-dot{background:var(--slate9)}
.cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;border:0;background:transparent;overflow:visible;margin-bottom:14px}
.cards article{min-height:112px;background:#fff;padding:16px 18px;border:0.5px solid var(--slate5);border-radius:6px;gap:4px}
.cards article:last-child{border-right:0.5px solid var(--slate5)}
.cards small{font-size:12px;color:var(--slate11)}
.cards strong,.cards strong.person{font-size:20px;line-height:28px;font-weight:650;color:var(--slate12)}
.cards span{font-size:12px;color:var(--slate10)}
.panel,.data-panel{background:#fff;border:0.5px solid var(--slate5);border-radius:6px;padding:18px 20px;margin-bottom:14px}
.data-panel{padding:18px 20px}
.panel-heading{min-height:34px;margin-bottom:12px}
.data-panel .panel-heading{border:0;margin:0 0 12px;padding:0}
.panel-heading h3{font-size:14px;font-weight:650}
.quick-start{padding:18px 20px;border:0.5px solid var(--slate5);background:#fff}
.quick-actions{gap:10px}.quick-actions :deep(.ant-btn){height:34px;background:#fff}
.empty-state{min-height:222px;border-top:0.5px solid var(--slate5);gap:12px}
.empty-icon{width:44px;height:44px;background:var(--blue3);border:0.5px solid var(--blue4);color:var(--blue11);border-radius:6px}
.shell :deep(.ant-btn-primary){background:var(--blue9);box-shadow:none}
.shell :deep(.ant-btn-primary:hover){background:var(--blue10)!important}
.shell :deep(.ant-btn-link),.shell :deep(.ant-typography a){color:var(--blue11)}
.shell :deep(.ant-btn-default){border:0.5px solid var(--slate7);box-shadow:none}
.shell :deep(.ant-card),.shell :deep(.ant-modal-content){box-shadow:none;border-radius:6px}
.shell :deep(.ant-table-thead>tr>th){background:var(--slate2);color:var(--slate11);border-bottom:0.5px solid var(--slate6);font-size:12px}
.shell :deep(.ant-table-tbody>tr>td){border-bottom:0.5px solid var(--slate5)}
.shell :deep(.ant-table-tbody>tr:hover>td){background:var(--blue3)!important}
.shell :deep(.ant-input:focus),.shell :deep(.ant-input-affix-wrapper-focused),
.shell :deep(.ant-select-focused .ant-select-selector){border-color:var(--blue9)!important;box-shadow:0 0 0 1px var(--blue4)!important}
@media(max-width:900px){.sidebar{width:176px}.content{padding:18px}.bar{padding:0 18px}}
@media(max-width:650px){.sidebar{width:64px;padding:12px 6px}.content{padding:14px}.cards{grid-template-columns:1fr}}
/* Layout-matched sections from the two supplied management screenshots */
.overview{display:grid;grid-template-columns:minmax(0,1fr) 268px;gap:14px;align-items:start}
.overview-main,.overview-aside{min-width:0;display:flex;flex-direction:column;gap:14px}
.overview-stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}
.metric{height:96px;background:#fff;border:0.5px solid var(--slate5);border-radius:6px;padding:16px;position:relative;display:flex;flex-direction:column;justify-content:center;gap:5px}
.metric>span:first-child{font-size:12px;color:var(--slate11)}.metric strong{font-size:20px;line-height:26px}
.metric-icon{position:absolute;right:12px;top:28px;display:grid;place-items:center;width:38px;height:38px;border-radius:50%;font-size:14px;font-weight:700}
.metric-icon.blue,.shortcut-icon.blue{color:#0d74ce;background:#edf6ff}
.metric-icon.cyan,.shortcut-icon.cyan{color:#0888a8;background:#def7f9}
.metric-icon.mint{color:#218358;background:#ddf8e8}.metric-icon.red{color:#ce2c31;background:#ffe5e5}
.shortcut-icon.violet{color:#6e56cf;background:#ede9fe}
.overview-block{margin:0;padding:18px}.overview-block .panel-heading{margin-bottom:12px}
.overview-shortcuts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}
.shortcut{border:0;background:#168cff;color:#fff;min-width:0;min-height:120px;padding:14px;text-align:left;border-radius:6px;display:flex;flex-direction:column;align-items:start;gap:7px;cursor:pointer}
.shortcut:nth-child(2){background:#0d74ce}.shortcut:nth-child(3){background:#3d88e8}
.shortcut .shortcut-icon{height:30px;width:30px;display:grid;place-items:center;border-radius:5px}
.shortcut strong{font-size:14px}.shortcut>span:last-child{font-size:12px;color:#edf6ff;margin-top:auto}
.overview-figures{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;padding:12px 0}
.overview-figures>div{display:flex;flex-direction:column;gap:12px}.overview-figures span{font-size:12px;color:var(--slate11)}.overview-figures strong{font-size:20px}
.security-overview{display:flex;align-items:center;flex-direction:column;padding:16px 0 10px}
.security-ring{height:130px;width:130px;border-radius:50%;display:grid;place-items:center;background:var(--slate5);position:relative}
.security-ring:before{content:"";position:absolute;inset:15px;background:#fff;border-radius:50%}.security-ring span{z-index:1;font-weight:650;font-size:20px}
.security-overview p{font-size:12px;margin:10px 0 0;color:var(--slate11)}
.security-legend{display:flex;gap:10px;justify-content:center;flex-wrap:wrap;font-size:12px;color:var(--slate11)}
.legend-dot{display:inline-block;width:7px;height:7px;border-radius:2px;margin-right:5px}.legend-dot.blue{background:#168cff}.legend-dot.red{background:#e5484d}
.todo-row{display:flex;align-items:center;gap:10px;padding:12px 0;border-bottom:0.5px solid var(--slate5);font-size:12px}.todo-row:last-child{border-bottom:0}.todo-row strong{margin-left:auto;font-size:14px}.todo-tag{background:#edf6ff;color:#0d74ce;border-radius:3px;padding:3px 6px}.todo-tag.alert-tag{background:#ffe5e5;color:#ce2c31}
.users-workspace{display:flex;flex-direction:column;gap:14px}.filter-panel{background:#fff;border:0.5px solid var(--slate5);border-radius:6px;padding:16px;display:flex;flex-wrap:wrap;align-items:center;gap:10px}
.filter-panel label{font-size:12px;color:var(--slate11)}.user-actions{display:flex;gap:10px}.user-table,.user-roles{margin:0}
.user-table :deep(.ant-table-thead>tr>th),.user-roles :deep(.ant-table-thead>tr>th){background:var(--slate3);font-size:12px}
.user-table :deep(.ant-table-tbody>tr>td),.user-roles :deep(.ant-table-tbody>tr>td){padding:11px 12px;font-size:12px}
.user-table :deep(.ant-pagination),.user-roles :deep(.ant-pagination){margin-top:18px}
@media(max-width:1150px){.overview{grid-template-columns:1fr}.overview-aside{display:grid;grid-template-columns:1fr 1fr}.overview-stats{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:700px){.overview-aside{grid-template-columns:1fr}.overview-shortcuts{grid-template-columns:1fr}.overview-stats{grid-template-columns:1fr 1fr}.filter-panel{align-items:stretch}}

/* Remove redundant top band on every authenticated page. */
.content{padding-top:0!important}
.content>section{margin-top:0}
.bar{height:44px!important;min-height:44px!important;padding-top:0;padding-bottom:0}
.bar .actions{gap:10px}
.account-trigger{display:inline-flex;align-items:center;gap:7px;padding:2px 7px 2px 2px;border:0;background:transparent;color:var(--slate12);font-size:12px;cursor:pointer;border-radius:5px;max-width:200px}
.account-trigger:hover{background:var(--slate3)}
.account-avatar{display:grid;place-items:center;width:25px;height:25px;flex:none;border-radius:50%;background:var(--blue3);color:var(--blue11);font-size:12px;font-weight:650}
.account-name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:600;max-width:120px}
.account-chevron{font-size:14px;color:var(--slate10)}
.account-detail{display:flex;flex-direction:column;gap:10px;min-width:175px;font-size:12px;color:var(--slate11)}
.account-detail strong{font-size:14px;color:var(--slate12)}
.shell .bar{height:44px;min-height:44px;padding:0 20px}
.shell .bar .actions{gap:10px}
.shell .bar .breadcrumb{font-size:12px}
.shell .account-trigger{height:30px;padding:2px 8px 2px 3px;border:0.5px solid var(--slate6);background:#fff;border-radius:5px;max-width:240px}
.shell .account-avatar{height:23px;width:23px}
.shell .account-name{font-size:12px;max-width:140px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.shell .logout-action{height:28px}
@media(max-width:650px){.shell .bar{padding:0 12px}.shell .bar .actions{gap:4px}.shell .account-name{max-width:65px}}
.bar{height:42px!important;min-height:42px;padding-inline:20px!important}
.account-trigger{min-height:32px;padding:2px 8px 2px 2px;gap:6px}
.account-avatar{width:24px;height:24px}
.account-detail{min-width:205px;gap:9px;padding:4px 2px}
.breadcrumb{font-size:12px}
/* Enterprise login with no fabricated marketing metrics */
.shell.auth-shell{display:block;background:#f8fafc;min-height:100dvh}
.auth-shell .main,.auth-shell .content{min-height:100dvh}
.auth-shell .content{margin:0;padding:0;max-width:none}
/* Desktop login: brand 60% / form 40%, shared by sign-in and password reset. */
.auth-layout{min-height:100dvh;display:grid;grid-template-columns:minmax(0,3fr) minmax(0,2fr)}
.auth-brand-panel{padding:42px clamp(36px,5vw,90px) 34px;background:linear-gradient(145deg,#e8f3ff,#d1e6fa);color:#172d46;display:flex;flex-direction:column}
.auth-brand-top{display:flex;align-items:center;gap:11px;font-weight:700;font-size:17px}
.auth-mark{display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:8px;background:#1677ed;color:#fff;font-size:19px;font-weight:750}
.auth-brand-body{max-width:460px;margin:auto 0;padding:44px 0}
.auth-eyebrow{font-size:11px;letter-spacing:2px;font-weight:700;color:#4286cb;margin-bottom:22px}
.auth-brand-body h1{font-size:clamp(28px,2.5vw,38px);line-height:1.42;font-weight:700;letter-spacing:-.8px;margin:0}
.auth-brand-body>p{font-size:14px;line-height:1.8;color:#637e9a;margin:17px 0 42px}
.auth-capabilities{display:flex;flex-direction:column;gap:25px}
.auth-capabilities>div{display:flex;align-items:flex-start;gap:15px}
.capability-icon{width:34px;height:34px;flex:none;display:grid;place-items:center;background:#ffffffad;border:1px solid #4f8fce21;border-radius:7px;color:#1677ed;font-size:12px;font-weight:700}
.auth-capabilities strong{display:block;font-size:14px;color:#28415e;font-weight:650}
.auth-capabilities small{display:block;margin-top:4px;font-size:12px;color:#68829d}
.auth-brand-foot{font-size:12px;color:#7990a8}
.auth-form-panel{min-width:0;min-height:100dvh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:48px 32px 24px;background:#f8fafc}
.auth-login-card{width:min(100%,396px);margin:auto 0}
.auth-mobile-brand{display:none}
.auth-login-heading{margin-bottom:36px}
.auth-login-heading h2{font-size:26px;font-weight:700;letter-spacing:-.5px;color:#192d42;margin:0 0 9px}
.auth-login-heading p{color:#7b8a9c;font-size:13px;line-height:1.7;margin:0}
.auth-form :deep(.ant-form-item){margin-bottom:22px}
.auth-form :deep(.ant-form-item-label>label){color:#35465b;font-weight:550;font-size:13px}
.auth-form :deep(.ant-input),.auth-form :deep(.ant-input-affix-wrapper){background:#fff!important;border:1px solid #dce3ec!important;border-radius:7px!important;min-height:44px;box-shadow:none!important}
.auth-form :deep(.ant-input:focus),.auth-form :deep(.ant-input-affix-wrapper-focused){border-color:#1677ed!important;box-shadow:0 0 0 3px #1677ed1a!important}
.auth-shell .auth-submit{height:44px;margin-top:8px;border-radius:7px;font-size:15px;font-weight:600;background:#1677ed;box-shadow:none}
.auth-shell .auth-cancel{height:42px;margin-top:12px;border-radius:7px}
.auth-support{font-size:12px;color:#8a98a9!important;text-align:center;margin-top:25px}
.auth-copyright{font-size:12px;color:#97a2b0;padding-top:24px;text-align:center}
.auth-shell .alert{position:fixed;bottom:26px;left:50%;transform:translateX(-50%);z-index:1000;max-width:calc(100vw - 32px);box-shadow:0 8px 32px #21334b18}
.shell:not(.auth-shell) .content{max-width:none;margin:0;padding:14px 14px 36px}
@media(max-width:1100px){.auth-brand-panel{padding:32px clamp(28px,4vw,56px) 28px}.auth-brand-body h1{font-size:clamp(27px,3vw,34px)}.auth-form-panel{padding:32px 24px 20px}}
/* On smaller screens prioritise a usable full-width form over a cramped 40% pane. */
@media(max-width:860px){.auth-layout{display:block}.auth-brand-panel{display:none}.auth-form-panel{padding:32px 24px;min-height:100dvh}.auth-mobile-brand{display:flex;align-items:center;gap:10px;margin-bottom:50px;font-size:17px;font-weight:700;color:#192d42}.auth-login-card{margin:auto 0}.auth-login-heading h2{font-size:24px}}

/* Compact dashboard density baseline (2026-10-23) */
.shell:not(.auth-shell) .content{padding:2px 2px 22px!important;max-width:none;margin:0}
.shell:not(.auth-shell) .overview{gap:3px;grid-template-columns:minmax(0,1fr) 268px}
.shell:not(.auth-shell) .overview-main,.shell:not(.auth-shell) .overview-aside{gap:3px}
.shell:not(.auth-shell) .overview-stats{gap:3px}
.shell:not(.auth-shell) .overview-shortcuts{gap:8px}
.shell:not(.auth-shell) .overview-block{padding:14px}
.shell:not(.auth-shell) .metric{border-radius:5px}
.shell:not(.auth-shell) .overview-block{border-radius:5px}
.shell:not(.auth-shell) .account-trigger{border:0!important;box-shadow:none!important;background:transparent!important;padding:2px 4px!important}
.shell:not(.auth-shell) .account-trigger:hover{background:var(--slate3)!important}
.shell:not(.auth-shell) .account-trigger:focus-visible{outline:2px solid var(--blue9);outline-offset:2px}
@media(max-width:1150px){.shell:not(.auth-shell) .overview{grid-template-columns:1fr}}
@media(max-width:700px){.shell:not(.auth-shell) .content{padding:4px!important}}

/* ===== MCP DataGuard international B2B dashboard visual baseline =====
   Source: approved compact reference (Oct 23 2026). Real metrics only.
   Colors: #087cfb / #119bf5 / #6e65f7; surfaces #fff / #f3f8ff.
*/
.shell:not(.auth-shell){--dg-ink:#101a33;--dg-muted:#63748e;--dg-canvas:#f2f7fd;--dg-primary:#087bfa;--dg-violet:#6c63f6;--dg-border:#e9f0fa;background:var(--dg-canvas);color:var(--dg-ink);font-family:Inter,system-ui,-apple-system,"Segoe UI","Microsoft YaHei",sans-serif}
.shell:not(.auth-shell) .sidebar{width:clamp(208px,15.3vw,256px);padding:20px 10px 22px;border-right:1px solid #e8eff8;background:#fff}
.shell:not(.auth-shell) .brand{gap:11px;padding:2px 14px 36px;font-size:16px;letter-spacing:-.15px;font-weight:750;color:#111b30}
.shell:not(.auth-shell) .brand small{font-size:12px;color:#7889a6;margin-top:2px;font-weight:400}
.shell:not(.auth-shell) .symbol{height:44px;width:44px;flex:none;border:0;border-radius:10px;background:linear-gradient(135deg,#d8eaff,#eef7ff);color:#0b7dfe;font-size:21px}
.shell:not(.auth-shell) .section{padding:0 17px;margin:0 0 12px;color:#899ab4;font-size:13px}
.shell:not(.auth-shell) .nav{display:flex;align-items:center;gap:17px;min-height:52px;padding:10px 18px;margin:4px 0;font-size:15px;color:#435879;border-radius:8px;white-space:nowrap}
.shell:not(.auth-shell) .nav svg{flex:none;color:#536a91}
.shell:not(.auth-shell) .nav.active{background:#eaf5ff;color:#0675ed;font-weight:700}
.shell:not(.auth-shell) .nav.active svg{color:#0675ed}
.shell:not(.auth-shell) .nav:hover{background:#f2f8ff;color:#087cfb}
.shell:not(.auth-shell) .foot{font-size:12px;margin:0 10px;padding:18px 6px 0;border-top:1px solid #e8eff8;color:#8191ac}
.shell:not(.auth-shell) .main{background:radial-gradient(ellipse 60% 32% at 81% 6%,#e6f0ff 0%,transparent 78%),var(--dg-canvas)}
.shell:not(.auth-shell) .bar{height:62px!important;min-height:62px!important;padding:0 26px!important;border-bottom:0;background:#ffffffb5}
.shell:not(.auth-shell) .breadcrumb{font-size:15px;gap:13px;color:#7385a2}
.shell:not(.auth-shell) .breadcrumb strong{color:#111a30;font-weight:700}
.shell:not(.auth-shell) .bar .actions{gap:13px}
.shell:not(.auth-shell) .alerts-trigger{position:relative;display:grid;place-items:center;width:33px;height:33px;cursor:pointer;background:transparent;border:0;color:#43577c;border-radius:50%}
.shell:not(.auth-shell) .alerts-trigger:hover{background:#e9f4ff;color:#087bfa}
.shell:not(.auth-shell) .notification-dot{position:absolute;width:6px;height:6px;border-radius:50%;background:#f04360;right:5px;top:3px}
.shell:not(.auth-shell) .account-separator{height:23px;width:1px;background:#e0e8f4}
.shell:not(.auth-shell) .account-trigger{height:38px;border:0!important;background:transparent!important;padding:2px 5px!important;gap:10px;font-size:14px;border-radius:8px;box-shadow:none!important}
.shell:not(.auth-shell) .account-trigger:hover{background:#ecf5ff!important}
.shell:not(.auth-shell) .account-avatar{height:36px;width:36px;background:#deefff;color:#087cfb;font-weight:700;font-size:15px}
.shell:not(.auth-shell) .account-name{font-size:14px;font-weight:650}
.shell:not(.auth-shell) .account-chevron{color:#6b7e9f}
.shell:not(.auth-shell) .content{padding:0 16px 26px!important;max-width:none;margin:0}
.dashboard-screen{width:100%;position:relative;isolation:isolate}
.dashboard-hero{height:112px;padding:15px 12px 13px;position:relative;overflow:hidden;z-index:0}
.dashboard-hero:before{content:"";position:absolute;z-index:-1;inset:0 -20px 0 29%;pointer-events:none;background:radial-gradient(ellipse 36% 70% at 55% 90%,#dceaffbb 0%,transparent 80%),radial-gradient(ellipse 43% 62% at 90% 78%,#e0e6ff9a 0%,transparent 85%);border-radius:0 0 36% 32%}
.dashboard-hero h1{font-size:clamp(24px,2.3vw,32px);font-weight:760;color:#0c1529;letter-spacing:-.8px;line-height:1.35;margin:0 0 7px}
.dashboard-hero p{color:#657999;font-size:14px;margin:0}
.shell:not(.auth-shell) .overview{display:grid;grid-template-columns:minmax(0,1fr) minmax(305px,29%);align-items:start;gap:10px}
.shell:not(.auth-shell) .overview-main,.shell:not(.auth-shell) .overview-aside{display:flex;flex-direction:column;min-width:0;gap:10px}
.shell:not(.auth-shell) .overview-stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0;background:#fff;border-radius:10px;min-height:110px;overflow:hidden;box-shadow:0 3px 18px #42639009}
.shell:not(.auth-shell) .metric{display:flex;flex-direction:row;align-items:center;text-align:left;justify-content:flex-start;position:relative;gap:16px;min-width:0;height:110px;padding:14px 18px;border:0;border-radius:0;cursor:pointer;background:transparent;color:var(--dg-ink)}
.shell:not(.auth-shell) .metric:not(:last-child):after{content:"";position:absolute;right:0;top:20%;bottom:20%;width:1px;background:#e2ebf6}
.shell:not(.auth-shell) .metric:hover{background:#f7fbff}
.metric-symbol,.metric-token,.figure-icon{display:grid;place-items:center;flex:none;border-radius:50%}
.metric-symbol{width:58px;height:58px}
.metric-agent,.figure-icon.blue{background:#e8f5ff;color:#0780fe}
.metric-db{background:#e5f9f1;color:#08bb83}
.metric-approval{background:#f0eaff;color:#8755fd}
.metric-copy{display:flex;flex-direction:column;gap:3px;min-width:0}
.metric-copy small{font-size:14px;white-space:nowrap;color:#5b6e8e}
.shell:not(.auth-shell) .metric .metric-copy strong{font-size:29px;line-height:33px;color:#0f1930;font-weight:750}
.metric-token{width:37px;height:37px;margin-left:auto;font-size:17px;font-weight:700}
.metric-token.blue{background:#eaf5ff;color:#087bfa}
.metric-token.cyan{background:#e0f8fa;color:#0aa6af}
.metric-token.red{background:#ffebee;color:#f34b63}
.shell:not(.auth-shell) .overview-block{border:0;border-radius:10px;background:#fff;padding:16px;margin:0;box-shadow:0 3px 18px #43628a08}
.overview-section-head{margin:0 0 10px;min-height:0}
.overview-section-head h2{font-size:19px;line-height:26px;font-weight:750;color:#10192b;margin:0}
.overview-section-head p{font-size:13px;color:#7285a4;margin:2px 0 0}
.overview-head-actions{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}
.overview-head-actions h2{margin-top:0}
.overview-text-link{display:inline-flex;align-items:center;gap:7px;padding:3px 0;background:transparent;border:0;color:#087cfb;font-size:13px;white-space:nowrap;cursor:pointer}
.overview-text-link:hover{text-decoration:underline}
.shell:not(.auth-shell) .overview-shortcuts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}
.shell:not(.auth-shell) .shortcut{position:relative;min-height:162px;padding:20px 19px 18px;border:0;border-radius:8px;overflow:hidden;display:flex;flex-direction:column;gap:0;color:#fff;text-align:left;cursor:pointer;transition:filter .2s,transform .2s}
.shell:not(.auth-shell) .shortcut:hover{filter:brightness(1.045);transform:translateY(-1px)}
.shortcut-agent,.shell:not(.auth-shell) .shortcut:nth-child(1){background:linear-gradient(135deg,#0675f7,#1599ff)}
.shortcut-db,.shell:not(.auth-shell) .shortcut:nth-child(2){background:linear-gradient(135deg,#0788f5,#1bb1fb)}
.shortcut-rules,.shell:not(.auth-shell) .shortcut:nth-child(3){background:linear-gradient(135deg,#7368ff,#6259ef)}
.shell:not(.auth-shell) .shortcut .shortcut-icon{display:grid;place-items:center;width:48px;height:48px;flex:none;border-radius:9px;background:#eef7ff;color:#067cff;z-index:1}
.shell:not(.auth-shell) .shortcut-rules .shortcut-icon{color:#7160f6}
.shell:not(.auth-shell) .shortcut strong{font-size:18px;line-height:28px;margin-top:11px;z-index:1;font-weight:740}
.shell:not(.auth-shell) .shortcut .shortcut-caption{font-size:12px;margin-top:1px;color:#f2f7ff;z-index:1;line-height:1.5}
.shell:not(.auth-shell) .shortcut .shortcut-watermark{position:absolute;right:17px;top:30px;opacity:.15;transform:rotate(-8deg);pointer-events:none}
.shell:not(.auth-shell) .shortcut .shortcut-arrow{position:absolute;right:15px;bottom:14px;display:grid;place-items:center;width:33px;height:33px;border-radius:50%;background:#ffffff34;color:#fff}
.shell:not(.auth-shell) .activity-panel{min-height:315px}
.shell:not(.auth-shell) .overview-figures{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0;margin-top:20px;padding:0}
.shell:not(.auth-shell) .figure{display:flex;flex-direction:row;align-items:flex-start;gap:14px;min-width:0;padding:0 12px}
.shell:not(.auth-shell) .figure:not(:last-child){border-right:1px solid #e1e9f5}
.shell:not(.auth-shell) .figure:first-child{padding-left:0}
.figure-icon{width:51px;height:51px}
.figure-icon.amber{background:#fff3e5;color:#ee9305}
.figure-icon.rose{background:#fff0f2;color:#f53e61}
.figure-content{display:flex;flex-direction:column;gap:3px;min-width:0}
.figure-content small{font-size:13px;color:#6b7c98;white-space:nowrap}
.shell:not(.auth-shell) .figure-content strong{font-size:29px;line-height:34px;font-weight:750;color:#0e1830}
.activity-empty{height:162px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:5px;color:#7a8bab;font-size:13px;text-align:center}
.activity-empty strong{font-weight:650;font-size:14px;color:#657996}
.activity-empty-icon{width:80px;height:73px;color:#c4daf7;position:relative;display:grid;place-items:center}
.activity-empty-icon svg:last-child{position:absolute;bottom:4px;right:1px;color:#8fb8f6;background:#fff;border-radius:50%}
.activity-preview{margin-top:30px;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:15px 16px;border-radius:8px;background:#f6faff;color:#6a7d9c;font-size:13px}
.activity-preview button{display:flex;align-items:center;gap:6px;border:0;background:none;color:#087cfb;cursor:pointer}
.shell:not(.auth-shell) .security-panel{min-height:270px}
.security-panel .overview-section-head{margin-bottom:0}
.shell:not(.auth-shell) .security-overview{display:flex;align-items:center;justify-content:center;padding:0;height:185px}
.shell:not(.auth-shell) .security-ring{width:175px;height:175px;display:flex;flex-direction:column;gap:0;align-items:center;justify-content:center;background:#e4e9f2;border-radius:50%;position:relative}
.shell:not(.auth-shell) .security-ring:before{content:"";position:absolute;inset:17px;border-radius:50%;background:#fff}
.shell:not(.auth-shell) .security-ring span,.shell:not(.auth-shell) .security-ring small{position:relative;z-index:1}
.shell:not(.auth-shell) .security-ring span{font-size:37px;font-weight:760;line-height:46px;color:#0e1831}
.shell:not(.auth-shell) .security-ring small{font-size:13px;color:#697d9a}
.shell:not(.auth-shell) .security-legend{gap:22px;font-size:12px;color:#657899;margin:1px 0 0}
.shell:not(.auth-shell) .legend-dot{width:9px;height:9px;border-radius:50%;margin-right:7px}
.shell:not(.auth-shell) .legend-dot.blue{background:#087dfd}
.shell:not(.auth-shell) .legend-dot.red{background:#f43e5d}
.shell:not(.auth-shell) .risk-panel{min-height:224px}
.risk-items{display:flex;flex-direction:column;gap:3px}
.risk-item{display:flex;align-items:center;gap:12px;border:0;background:transparent;width:100%;text-align:left;min-height:49px;padding:5px 0;cursor:pointer;color:var(--dg-ink)}
.risk-item:hover{background:#f6faff}
.risk-item-icon{width:36px;height:36px;border-radius:50%;background:#ffedf0;color:#f0485c;display:grid;place-items:center;flex:none}
.risk-item-body{min-width:0;display:flex;flex-direction:column;gap:1px;flex:1}
.risk-item-body strong{font-size:12px;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.risk-item-body small{font-size:11px;color:#8596b0}
.risk-item time{font-size:11px;color:#8a9bb5;white-space:nowrap}
.risk-empty{min-height:143px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;color:#8394af;font-size:12px}
.risk-empty strong{font-size:13px;color:#5f7697}
.risk-clear-icon{color:#27ae81;background:#e9f9f2;border-radius:50%;width:44px;height:44px;display:grid;place-items:center;margin-bottom:2px}
.shell:not(.auth-shell) .todo-panel{min-height:166px}
.shell:not(.auth-shell) .todo-panel .overview-section-head{margin-bottom:8px}
.shell:not(.auth-shell) .todo-row{width:100%;display:flex;align-items:center;gap:10px;border:0;border-bottom:1px solid #e8eef7;background:transparent;text-align:left;padding:11px 0;font-size:12px;color:#202f49;cursor:pointer}
.shell:not(.auth-shell) .todo-row:last-child{border-bottom:0}
.shell:not(.auth-shell) .todo-row:hover{background:#f8fbff}
.shell:not(.auth-shell) .todo-row strong{margin-left:auto;color:#111d36;font-size:14px}
.shell:not(.auth-shell) .todo-row svg{color:#8192ad}
.shell:not(.auth-shell) .todo-tag{border-radius:5px;background:#e9f5ff;color:#087cfb;font-size:12px;padding:6px 9px}
.shell:not(.auth-shell) .todo-tag.alert-tag{background:#ffedf0;color:#ed4259}
@media(max-width:1200px){.shell:not(.auth-shell) .overview{grid-template-columns:minmax(0,1fr) minmax(270px,30%)}.shell:not(.auth-shell) .metric{padding:10px;gap:9px}.metric-symbol{width:48px;height:48px}.metric-token{width:30px;height:30px}}
@media(max-width:1050px){.shell:not(.auth-shell) .overview{grid-template-columns:1fr}.shell:not(.auth-shell) .overview-aside{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.shell:not(.auth-shell) .todo-panel{grid-column:1/-1}}
@media(max-width:700px){.shell:not(.auth-shell) .sidebar{width:64px;padding:14px 5px}.shell:not(.auth-shell) .brand{padding:0 8px 28px}.shell:not(.auth-shell) .brand div,.shell:not(.auth-shell) .section,.shell:not(.auth-shell) .foot,.shell:not(.auth-shell) .nav span{display:none}.shell:not(.auth-shell) .nav{justify-content:center;gap:0;padding:12px 5px}.shell:not(.auth-shell) .content{padding:0 8px 18px!important}.dashboard-hero{height:92px;padding:12px 5px}.dashboard-hero h1{font-size:23px}.dashboard-hero p{font-size:12px}.shell:not(.auth-shell) .overview-stats{grid-template-columns:1fr 1fr}.shell:not(.auth-shell) .metric{height:96px}.shell:not(.auth-shell) .overview-shortcuts{grid-template-columns:1fr}.shell:not(.auth-shell) .shortcut{min-height:130px}.shell:not(.auth-shell) .overview-aside{grid-template-columns:1fr}.shell:not(.auth-shell) .overview-figures{gap:6px}.shell:not(.auth-shell) .figure{padding:0 5px;gap:7px}.figure-icon{width:36px;height:36px}.figure-content small{white-space:normal;font-size:11px}.shell:not(.auth-shell) .figure-content strong{font-size:23px}}

/*
 * Global authenticated-page gutters
 * Keep the first-level page panels nearly flush with the sidebar and
 * the right viewport edge. Applies to every page, not only dashboard.
 */
.shell:not(.auth-shell) .content{
  width:100%;
  max-width:none;
  margin:0;
  padding:0 2px 22px!important;
}
.shell:not(.auth-shell) .content>section,
.shell:not(.auth-shell) .content>template{
  max-width:100%;
}
@media(max-width:700px){
  .shell:not(.auth-shell) .content{
    padding:0 4px 18px!important;
  }
}

/* Reusable approved DataGuard shield/database brand artwork on authentication views. */
.auth-brand-top{gap:12px;min-height:60px}
.auth-brand-mark{width:54px;height:59px;flex:none;object-fit:contain}
.auth-brand-copy{display:flex;flex-direction:column;min-width:0;gap:2px}
.auth-brand-copy .brand-wordmark{display:block;font-size:20px;line-height:1.2;font-weight:760;letter-spacing:-.5px;color:#142a4c}
.auth-brand-copy .brand-wordmark span{color:#086af1}
.auth-brand-copy small{display:block;font-size:12px;line-height:1.4;color:#687f9c;font-weight:500}
.auth-mobile-brand{gap:9px}
.auth-mobile-brand .auth-brand-mark{width:44px;height:48px}
.auth-mobile-brand .auth-brand-copy .brand-wordmark{font-size:18px}
@media(max-width:1100px){.auth-brand-top .auth-brand-mark{width:48px;height:52px}.auth-brand-top .brand-wordmark{font-size:19px}}

/* Five-minute aggregate metrics use compact typography and no fabricated data. */
.shell:not(.auth-shell) .live-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}
.shell:not(.auth-shell) .live-metrics>div{display:flex;flex-direction:column;gap:7px;padding:12px 14px;background:#f7faff;border-radius:7px}
.shell:not(.auth-shell) .live-metrics small{font-size:12px;color:#7486a1}
.shell:not(.auth-shell) .live-metrics strong{font-size:23px;line-height:1.1;color:#193351}
.shell:not(.auth-shell) .live-metrics strong span{font-size:11px;font-weight:500;color:#8394aa}
@media(max-width:1000px){.shell:not(.auth-shell) .live-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:500px){.shell:not(.auth-shell) .live-metrics>div{padding:9px 10px}.shell:not(.auth-shell) .live-metrics strong{font-size:20px}}

/* Scoped selector intentionally overrides the dashboard's scoped 62px bar.
   Previously a global selector lost the cascade in real Chromium. */
.shell.compact-page:not(.auth-shell) .bar{height:46px!important;min-height:46px!important;padding:0 18px!important}
@media(max-width:760px){.shell.compact-page:not(.auth-shell) .bar{height:46px!important;min-height:46px!important;padding:0 9px!important}}

.shell:not(.auth-shell) .security-alert-filters{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:0 0 10px}
@media(max-width:700px){.shell:not(.auth-shell) .security-alert-filters .ant-select{flex:1;min-width:120px}}
</style>
