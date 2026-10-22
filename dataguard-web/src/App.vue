<script setup lang="ts">
import {ref,onMounted,computed,reactive} from 'vue';
import {message} from 'ant-design-vue';
import {auth,configured} from './auth';
const pages=[
 ['dashboard','总览仪表盘'],['agents','Agent 管理'],['datasources','数据源管理'],
 ['rules','SQL 安全规则'],['flowable','Flowable 审批'],['audit','SQL 审计与监控'],
 ['users','用户与权限']
];
const selected=ref('dashboard');
const token=ref('');
const profile=ref<{orgId:string;userId:string;username:string;displayName:string}|null>(null);
const agents=ref<Record<string,unknown>[]>([]);
const usersList=ref<Record<string,unknown>[]>([]);
const rolesList=ref<{id:string;code:string;name:string;permissions:string;systemRole:boolean}[]>([]);
const userDialog=ref(false),roleDialog=ref(false),enrollDialog=ref(false),credentialDialog=ref(false);
const userForm=reactive({issuer:import.meta.env.VITE_OIDC_AUTHORITY||'',subject:'',username:'',displayName:''});
const roleForm=reactive({code:'',name:'',permissions:['agent:read','datasource:read'] as string[]});
const enrollForm=reactive({agentId:'',issuer:import.meta.env.VITE_OIDC_AUTHORITY||'',
  subject:'',audience:'mcp-dataguard',clientId:''});
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
async function reload(){
 if(!token.value)return;busy.value=true;error.value='';
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

async function requestWrite(path:string,method:'POST'|'PUT',payload?:unknown){
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
 if(!token.value)return;
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
  userDialog.value=false;message.success('OIDC 用户登记成功；角色需另行分配。');
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
 Object.assign(enrollForm,{agentId:String(record.id),subject:'',clientId:'',audience:'mcp-dataguard'});
 enrollDialog.value=true;
}
async function saveEnroll(){
 saving.value=true;error.value='';
 try{
  const {agentId,...payload}=enrollForm;
  await requestWrite('/api/v1/agents/'+agentId+'/enroll','POST',payload);
  enrollDialog.value=false;message.success('Agent OIDC 身份已绑定，仍需授权可访问的数据源。');
  await reload();
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
async function login(){if(auth)await auth.signinRedirect();}


async function logout(){if(auth)await auth.removeUser();token.value='';profile.value=null;agents.value=[];datasources.value=[];}
onMounted(async()=>{
 if(!auth)return;
 try{
  if(location.pathname==='/auth/callback'){await auth.signinRedirectCallback();history.replaceState(null,'','/');}
  const user=await auth.getUser();
  if(user&&!user.expired){token.value=user.access_token;await reload();await loadAdmin();}
 }catch(e){error.value=(e as Error).message;}
});
</script>
<template>
 <div class="shell">
  <aside class="sidebar">
   <div class="brand"><span class="symbol">◆</span><div>MCP DataGuard<small>AI DATABASE SECURITY</small></div></div>
   <span class="section">工作台</span>
   <button v-for="page in pages" :key="page[0]" :class="['nav',{active:selected===page[0]}]" @click="selected=page[0];if(page[0]==='users')loadAdmin()">{{page[1]}}</button>
   <div class="foot">● 开源研发版 · v0.5</div>
  </aside>
  <main class="main">
   <header class="bar"><div>工作台 / <strong>{{title}}</strong></div>
    <div class="actions"><a-button size="small" :disabled="!token" :loading="busy" @click="reload">刷新</a-button>
     <a-button v-if="!token" size="small" type="primary" :disabled="!configured" @click="login">OIDC 登录</a-button>
     <a-button v-else size="small" @click="logout">退出</a-button>
    </div></header>
   <div class="content">
    <div class="heading"><div><h1>{{title}}</h1><p>AI Agent 多数据库安全访问与管理平台</p></div><span class="status">开发中 · 写入禁用</span></div>
    <a-alert v-if="!configured" type="warning" show-icon message="请先配置 OIDC：dataguard-web/.env.local"/>
    <a-alert v-else-if="!profile" type="info" show-icon message="请登录已登记的平台用户；不支持匿名访问。"/>
    <a-alert v-if="error" type="warning" show-icon :message="error" class="alert"/>
    <template v-if="profile">
     <section v-if="selected==='dashboard'">
      <div class="cards">
       <article><small>当前用户</small><strong class="person">{{profile.displayName}}</strong><span>{{profile.username}}</span></article>
       <article><small>Agent 数量（当前页）</small><strong>{{agents.length}}</strong><span>真实授权数据</span></article>
       <article><small>数据源数量（当前页）</small><strong>{{datasources.length}}</strong><span>真实授权数据</span></article>
      </div>
      <div class="panel"><h3>欢迎使用 MCP DataGuard</h3><p>你属于组织 {{profile.orgId}}。当前仅实现 Agent 和数据源的只读查询；其他业务模块仍在开发中。所有危险 SQL 操作默认拒绝。</p></div>
     </section>
     <section v-else-if="selected==='agents'" class="panel">
      <div class="panel-heading"><h3>Agent 列表</h3><a-button size="small" type="primary" @click="newAgent">新增 Agent</a-button></div>
      <a-table row-key="id" :columns="columnsAgents" :data-source="agents" size="small" :loading="busy" :pagination="{pageSize:10}">
       <template #bodyCell="{column,record}">
        <template v-if="column.key==='actions'">
         <a-button size="small" type="link" @click="editAgent(record)">编辑</a-button>
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
         <a-button size="small" type="link" @click="testDatasource(record)">测试连接</a-button>
         <a-popconfirm v-if="record.status!=='DISABLED'" title="确认停用数据源？" ok-text="停用" cancel-text="取消" @confirm="disableDatasource(record)">
          <a-button size="small" type="link" danger>停用</a-button>
         </a-popconfirm>
        </template>
       </template>
      </a-table>
     </section>

     <section v-else-if="selected==='users'" class="panel">
      <div class="panel-heading">
       <h3>用户与组织角色</h3>
       <div class="actions"><a-button size="small" @click="loadAdmin">刷新</a-button>
        <a-button size="small" @click="userDialog=true">登记 OIDC 用户</a-button>
        <a-button size="small" type="primary" @click="roleDialog=true">创建角色</a-button>
       </div>
      </div>
      <a-alert type="info" show-icon message="只允许委派基础管理权限；不能通过此页面授予 SQL 执行权限。"/>
      <h4 style="margin-top:24px">组织用户</h4>
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
    <p class="disclaimer">必须使用有效 OIDC JWT、后端权限校验和隔离的数据库只读账户。当前版本不得连接生产数据库执行 SQL。</p>

   </div>
  </main>

  <a-modal v-model:open="userDialog" title="登记 OIDC 用户" ok-text="保存"
   :confirm-loading="saving" @ok="saveUser">
   <a-form layout="vertical">
    <a-form-item label="OIDC Issuer"><a-input v-model:value="userForm.issuer"/></a-form-item>
    <a-form-item label="OIDC Subject (sub)"><a-input v-model:value="userForm.subject"/></a-form-item>
    <a-form-item label="用户名"><a-input v-model:value="userForm.username"/></a-form-item>
    <a-form-item label="显示名称"><a-input v-model:value="userForm.displayName"/></a-form-item>
    <a-alert type="info" show-icon message="OIDC 用户必须已经存在于身份提供方；本操作不创建密码。"/>
   </a-form>
  </a-modal>
  <a-modal v-model:open="roleDialog" title="创建组织角色" ok-text="保存"
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
  <a-modal v-model:open="enrollDialog" title="绑定 Agent 的 OIDC 机器身份" ok-text="绑定"
   :confirm-loading="saving" @ok="saveEnroll">
   <a-form layout="vertical">
    <a-form-item label="签发者 Issuer"><a-input v-model:value="enrollForm.issuer"/></a-form-item>
    <a-form-item label="机器账户 Subject"><a-input v-model:value="enrollForm.subject"/></a-form-item>
    <a-form-item label="Audience"><a-input v-model:value="enrollForm.audience"/></a-form-item>
    <a-form-item label="OIDC Client ID"><a-input v-model:value="enrollForm.clientId"/></a-form-item>
    <a-alert type="warning" show-icon message="只绑定在 IdP 已登记的独立机器身份；不可使用人员账号。"/>
   </a-form>
  </a-modal>
  <a-modal v-model:open="credentialDialog" title="配置服务端只读凭据引用" ok-text="保存"
   :confirm-loading="saving" @ok="saveCredentials">
   <a-form layout="vertical">
    <a-form-item label="环境变量前缀"><a-input v-model:value="credentialForm.secretRef"
      placeholder="DG_TARGET_REPORTING_READ"/></a-form-item>
    <a-alert type="info" show-icon message="服务端需配置前缀_USER / 前缀_PASSWORD；不要把密码写在此处。"/>
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
    <a-alert type="info" show-icon message="默认停用，不会自动授予数据库权限。"/>
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
    <a-alert type="info" show-icon message="仅保存数据源元数据，不测试连接、不存储明文密码，默认禁用。"/>
   </a-form>
  </a-modal>
 </div>
</template>
<style scoped>
*{box-sizing:border-box}.shell{min-height:100vh;display:flex;background:#f6f8fb;color:#1c2e4b;font-family:system-ui,"Microsoft YaHei",sans-serif}
.sidebar{width:238px;flex:none;background:#fff;border-right:1px solid #e5ebf2;padding:24px 12px;display:flex;flex-direction:column}
.brand{display:flex;align-items:center;gap:11px;font-weight:700;font-size:17px;padding:4px 12px 25px}.brand small{display:block;font-size:9px;color:#9baac0;letter-spacing:1.1px;margin-top:4px}.symbol{display:grid;place-items:center;background:#e6f0ff;color:#2679da;width:37px;height:37px;border-radius:10px}
.section{font-size:11px;color:#9baac0;margin:4px 13px 12px}.nav{border:0;display:block;text-align:left;border-radius:8px;background:transparent;color:#596d8a;padding:12px;margin:1px 0;cursor:pointer;font-size:13px}.nav:hover{background:#f7faff}.nav.active{background:#eaf3ff;color:#2570d0;font-weight:600}
.foot{margin-top:auto;border-top:1px solid #edf0f6;padding:17px 10px;color:#869bb5;font-size:12px}.main{min-width:0;flex:1}.bar{height:65px;background:white;border-bottom:1px solid #e5ebf2;display:flex;align-items:center;justify-content:space-between;padding:0 26px;font-size:13px;color:#8fa0b6}.bar strong{color:#354e6c;margin-left:8px}.actions{display:flex;gap:8px}
.content{padding:30px;max-width:1300px;margin:0 auto}.heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:25px;gap:16px}.heading h1{font-size:23px;margin:0 0 8px}.heading p{font-size:13px;color:#8595a9;margin:0}.status{font-size:12px;color:#ad7833;background:#fff5e4;border:1px solid #ffdfa9;border-radius:18px;padding:7px 12px;white-space:nowrap}
.alert{margin:12px 0}.cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin-bottom:20px}.cards article{background:#fff;border:1px solid #e6edf5;border-radius:12px;padding:20px;display:flex;flex-direction:column;gap:10px}.cards small,.cards span{color:#97a7ba}.cards strong{font-size:31px}.cards strong.person{font-size:22px}.panel{background:#fff;border:1px solid #e6edf5;border-radius:12px;padding:23px;margin-bottom:18px}.panel h3{font-size:16px;margin:0 0 17px}.assignment{display:flex;gap:10px;flex-wrap:wrap;margin:15px 0}.panel-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px}.panel-heading h3{margin:0}.form-pair{display:grid;grid-template-columns:1fr 1fr;gap:14px}.form-pair :deep(.ant-form-item){min-width:0}.form-pair :deep(.ant-input-number),.form-pair :deep(.ant-select){width:100%}.panel p{font-size:13px;line-height:1.8;color:#7d8ba0}.placeholder{text-align:center;padding:65px 30px}.disclaimer{font-size:11px;color:#a1aec0;margin-top:30px}
@media(max-width:850px){.sidebar{width:180px}.content{padding:18px}.cards{grid-template-columns:1fr}.bar{padding:0 16px}}
@media(max-width:570px){.sidebar{width:62px;padding:20px 7px}.brand div,.section,.nav{font-size:0}.brand{padding:0 5px 25px}.nav:after{content:"•";font-size:17px}.foot{display:none}}
</style>
