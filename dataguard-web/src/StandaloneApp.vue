<script setup lang="ts">
import {ref,onMounted} from 'vue';
import {message,Modal} from 'ant-design-vue';
type Obj=Record<string,any>;
type User={username:string;displayName:string;roleCode:string;mustChangePassword:boolean};
const me=ref<User|null>(null),csrf=ref(''),busy=ref(false),tab=ref('dashboard');
const users=ref<Obj[]>([]),sources=ref<Obj[]>([]),approvers=ref<Obj[]>([]),tasks=ref<Obj[]>([]),agents=ref<Obj[]>([]),audits=ref<Obj[]>([]);
const newUser=ref({username:'',displayName:'',roleCode:'READER',temporaryPassword:''});
const newSource=ref({code:'',displayName:'',host:'127.0.0.1',port:3306,databaseName:'',readUsername:'',passwordEnv:''});
const newApproval=ref({sourceId:'',tableName:'',approverId:'',reason:''});
const password=ref({currentPassword:'',newPassword:''});
const tokenForm=ref({label:'',validDays:7});
const once=ref('');
const menu=[['dashboard','概览'],['users','用户管理'],['sources','数据源与白名单'],['request','申请读取'],['approvals','我的待审批'],['agents','Agent 令牌'],['audit','审计日志']];
async function call(path:string,method='GET',body?:Obj){
 const res=await fetch(path,{method,credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.value},body:body?JSON.stringify(body):undefined});
 if(res.status===401){location.href='/login.html';throw Error('会话已失效')}
 if(!res.ok)throw Error('HTTP '+res.status);
 return res.json();
}
async function refresh(){
 try{
  if(tab.value==='users')users.value=await call('/api/users');
  if(tab.value==='sources')sources.value=await call('/api/sources');
  if(tab.value==='request'){
   sources.value=(await call('/api/sources')).filter((s:Obj)=>s.enabled);
   approvers.value=(await call('/api/users/approvers')).filter((u:Obj)=>u.username!==me.value?.username);
  }
  if(tab.value==='approvals')tasks.value=await call('/api/approvals/my');
  if(tab.value==='agents')agents.value=await call('/api/agents');
  if(tab.value==='audit')audits.value=await call('/api/audit');
 }catch(e){message.error((e as Error).message)}
}
async function submit(path:string,body:Obj,after=true){
 if(busy.value)return null;
 busy.value=true;
 try{const result=await call(path,'POST',body);message.success('操作成功');if(after)await refresh();return result}
 catch(e){message.error((e as Error).message);return null}
 finally{busy.value=false}
}
async function createUser(){if(await submit('/api/users',newUser.value))newUser.value={username:'',displayName:'',roleCode:'READER',temporaryPassword:''}}
async function createSource(){if(await submit('/api/sources',newSource.value))newSource.value={code:'',displayName:'',host:'127.0.0.1',port:3306,databaseName:'',readUsername:'',passwordEnv:''}}
async function allow(sourceId:string,kind:'table'|'column'){
 const table=window.prompt('输入白名单表名');if(!table)return;
 if(kind==='table'){await submit('/api/sources/'+sourceId+'/allowlist',{tableName:table});return}
 const column=window.prompt('请输入已审核可展示的非敏感字段名');if(column)await submit('/api/sources/'+sourceId+'/allowlist/'+encodeURIComponent(table)+'/columns',{columnName:column});
}
async function decision(task:Obj,action:string){
 const reason=window.prompt('请输入审批意见（必填）');
 if(reason)await submit('/api/approvals/'+task.id+'/'+action,{reason});
}
async function token(){
 const result=await submit('/api/agents',{label:tokenForm.value.label,validDays:Number(tokenForm.value.validDays)});
 if(result){once.value=result.token;tokenForm.value={label:'',validDays:7}}
}
async function changePassword(){
 const result=await submit('/api/me/password',password.value,false);
 if(result?.reauthenticate)location.href='/login.html';
}
function logout(){
 const f=document.createElement('form');f.action='/auth/logout';f.method='post';
 const h=document.createElement('input');h.type='hidden';h.name='_csrf';h.value=csrf.value;f.append(h);document.body.append(f);f.submit();
}
function onMenuClick(event:{key:string|number}){void switchTab(String(event.key))}
async function switchTab(name:string){if(me.value?.mustChangePassword)return;tab.value=name;await refresh()}
onMounted(async()=>{
 try{
  csrf.value=(await (await fetch('/api/csrf',{credentials:'same-origin'})).json()).token;
  me.value=await call('/api/me');
  if(me.value?.mustChangePassword)tab.value='password';
  else await refresh();
 }catch(e){message.error('身份验证失败：'+(e as Error).message)}
});
</script>

<template>
<div class="standalone">
 <aside class="side" v-if="!me?.mustChangePassword">
  <div class="brand"><span class="mark">D</span>DataGuard</div>
  <div class="sub">企业内部数据安全平台</div>
  <a-menu v-model:selectedKeys="[tab]" mode="inline" class="nav" @click="onMenuClick">
   <a-menu-item v-for="[id,name] in menu" :key="id">{{name}}</a-menu-item>
  </a-menu>
  <div class="side-note">MySQL · Flowable · MCP</div>
 </aside>
 <main class="workspace">
  <header class="top"><div><h1>{{tab==='password'?'首次登录安全设置':(menu.find(x=>x[0]===tab)?.[1]||'概览')}}</h1><p>统一身份 · 最小权限 · 全程审计</p></div><div class="account">{{me?.displayName}} <a-tag>{{me?.roleCode}}</a-tag><a-button @click="logout">退出登录</a-button></div></header>
  <template v-if="tab==='password'">
   <a-card class="panel" title="首次登录必须修改密码" style="max-width:600px">
    <p>新密码至少 12 位，包含大写、小写、数字和特殊符号；完成后重新登录。</p>
    <a-form layout="vertical"><a-form-item label="原始密码"><a-input-password v-model:value="password.currentPassword"/></a-form-item>
    <a-form-item label="新密码"><a-input-password v-model:value="password.newPassword"/></a-form-item>
    <a-button type="primary" :loading="busy" @click="changePassword">修改密码并重新登录</a-button></a-form>
   </a-card>
  </template>
  <template v-else-if="tab==='dashboard'">
   <a-row :gutter="16"><a-col :span="8" v-for="x in [{n:'访问控制',d:'表级与字段级正向白名单'},{n:'业务审批',d:'仅系统内部真实用户参与'},{n:'安全审计',d:'登录、变更及读取全链路留痕'}]" :key="x.n"><a-card class="panel"><h3>{{x.n}}</h3><p>{{x.d}}</p></a-card></a-col></a-row>
   <a-alert class="notice" type="info" show-icon message="默认拒绝访问" description="只有被明确允许的 MySQL 表及字段，并且经过本人有效审批，才可通过 MCP 或控制台执行受限读取。"/>
  </template>
  <template v-else-if="tab==='users'">
   <a-card class="panel" title="创建内部用户"><a-form layout="inline">
    <a-form-item><a-input v-model:value="newUser.username" placeholder="用户名"/></a-form-item><a-form-item><a-input v-model:value="newUser.displayName" placeholder="姓名"/></a-form-item>
    <a-form-item><a-select v-model:value="newUser.roleCode" style="width:125px"><a-select-option value="READER">读取人员</a-select-option><a-select-option value="APPROVER">审批人员</a-select-option><a-select-option value="ADMIN">管理员</a-select-option></a-select></a-form-item>
    <a-form-item><a-input-password v-model:value="newUser.temporaryPassword" placeholder="临时复杂密码"/></a-form-item><a-button type="primary" :loading="busy" @click="createUser">创建</a-button></a-form></a-card>
   <a-card class="panel" title="系统用户"><a-table :data-source="users" :pagination="{pageSize:10}" row-key="id"><a-table-column key="username" title="用户名" data-index="username"/><a-table-column key="displayName" title="姓名" data-index="displayName"/><a-table-column key="roleCode" title="角色" data-index="roleCode"/><a-table-column key="enabled" title="状态"><template #default="{record}"><a-tag :color="record.enabled?'green':'default'">{{record.enabled?'启用':'禁用'}}</a-tag></template></a-table-column></a-table></a-card>
  </template>
  <template v-else-if="tab==='sources'">
   <a-card class="panel" title="新建 MySQL 数据源"><a-form layout="inline">
    <a-form-item><a-input v-model:value="newSource.code" placeholder="编码"/></a-form-item><a-form-item><a-input v-model:value="newSource.displayName" placeholder="名称"/></a-form-item><a-form-item><a-input v-model:value="newSource.host" placeholder="主机名"/></a-form-item>
    <a-form-item><a-input-number v-model:value="newSource.port" :min="1" :max="65535"/></a-form-item>
    <a-form-item><a-input v-model:value="newSource.databaseName" placeholder="数据库"/></a-form-item><a-form-item><a-input v-model:value="newSource.readUsername" placeholder="物理只读账号"/></a-form-item><a-form-item><a-input v-model:value="newSource.passwordEnv" placeholder="密钥环境变量名"/></a-form-item>
    <a-button type="primary" :loading="busy" @click="createSource">创建（默认关闭）</a-button></a-form></a-card>
   <a-card class="panel" title="数据源与读取白名单"><a-table :data-source="sources" row-key="id" :pagination="{pageSize:10}">
     <a-table-column title="名称" data-index="displayName"/><a-table-column title="地址" data-index="host"/><a-table-column title="库" data-index="databaseName"/>
     <a-table-column title="启用状态"><template #default="{record}"><a-tag :color="record.enabled?'green':'default'">{{record.enabled?'已启用':'未启用'}}</a-tag></template></a-table-column>
     <a-table-column title="操作"><template #default="{record}"><a-space><a-button size="small" @click="allow(record.id,'table')">添加表白名单</a-button><a-button size="small" @click="allow(record.id,'column')">批准字段</a-button><a-button v-if="!record.enabled" size="small" type="primary" @click="submit('/api/sources/'+record.id+'/enable',{})">启用</a-button></a-space></template></a-table-column>
   </a-table></a-card>
  </template>
  <template v-else-if="tab==='request'">
   <a-card class="panel" title="申请读取权限"><a-form layout="vertical" style="max-width:520px">
    <a-form-item label="MySQL 数据源"><a-select v-model:value="newApproval.sourceId" placeholder="选择已启用的数据源"><a-select-option v-for="s in sources" :key="s.id" :value="s.id">{{s.displayName}}</a-select-option></a-select></a-form-item>
    <a-form-item label="白名单表"><a-input v-model:value="newApproval.tableName" placeholder="输入已授权表名"/></a-form-item>
    <a-form-item label="指定系统内审批人"><a-select v-model:value="newApproval.approverId" placeholder="只能选择已启用的审批用户"><a-select-option v-for="u in approvers" :key="u.id" :value="u.id">{{u.displayName}} ({{u.username}})</a-select-option></a-select></a-form-item>
    <a-form-item label="业务原因"><a-textarea v-model:value="newApproval.reason" :rows="3"/></a-form-item><a-button type="primary" :loading="busy" @click="submit('/api/approvals',newApproval)">提交审批</a-button></a-form>
   </a-card>
  </template>
  <template v-else-if="tab==='approvals'"><a-card class="panel" title="我的待审批"><a-table :data-source="tasks" row-key="id"><a-table-column title="申请人" data-index="requester"/><a-table-column title="读取表" data-index="tableName"/><a-table-column title="原因" data-index="reason"/><a-table-column title="审批操作"><template #default="{record}"><a-space><a-button type="primary" size="small" @click="decision(record,'approve')">通过</a-button><a-button danger size="small" @click="decision(record,'reject')">拒绝</a-button></a-space></template></a-table-column></a-table></a-card></template>
  <template v-else-if="tab==='agents'">
   <a-card class="panel" title="创建 MCP Agent 凭据"><a-space><a-input v-model:value="tokenForm.label" placeholder="Agent 名称"/><a-input-number v-model:value="tokenForm.validDays" :min="1" :max="90" addon-after="天"/><a-button type="primary" :loading="busy" @click="token">创建</a-button></a-space><a-alert v-if="once" style="margin-top:16px" type="warning" message="令牌只展示一次，请安全保存"><template #description><code style="overflow-wrap:anywhere">{{once}}</code> <a-button size="small" @click="once=''">清除</a-button></template></a-alert></a-card>
   <a-card class="panel" title="我的 Agent"><a-table :data-source="agents" row-key="id"><a-table-column title="名称" data-index="label"/><a-table-column title="到期时间" data-index="expiresAt"/><a-table-column title="状态"><template #default="{record}">{{record.revokedAt?'已撤销':'有效'}}</template></a-table-column><a-table-column title="操作"><template #default="{record}"><a-button v-if="!record.revokedAt" danger @click="submit('/api/agents/'+record.id+'/revoke',{})">撤销</a-button></template></a-table-column></a-table></a-card>
  </template>
  <template v-else-if="tab==='audit'"><a-card class="panel" title="操作审计（最近 200 条）"><a-table :data-source="audits" row-key="id" :pagination="{pageSize:15}"><a-table-column title="时间" data-index="event_time"/><a-table-column title="操作人" data-index="actor_username"/><a-table-column title="动作" data-index="action"/><a-table-column title="资源" data-index="resource_id"/><a-table-column title="结果" data-index="outcome"/></a-table></a-card></template>
 </main>
</div>
</template>

<style scoped>
.standalone{display:flex;min-height:100vh;background:#f5f7fa;color:#202939}.side{width:228px;flex-shrink:0;background:#fff;border-right:1px solid #e5e9ef;display:flex;flex-direction:column;padding:28px 12px}.brand{font-size:21px;font-weight:750;letter-spacing:-.6px;padding:0 15px;display:flex;align-items:center;gap:9px}.mark{background:#1677ff;color:#fff;border-radius:9px;width:30px;height:30px;display:grid;place-items:center;font-size:17px}.sub{color:#93a0b4;font-size:11px;margin:10px 15px 25px}.nav{border:0;background:transparent}.side-note{margin-top:auto;padding:12px;text-align:center;color:#9aa6b5;font-size:11px}.workspace{min-width:0;flex:1;padding:28px 36px;max-width:1600px}.top{display:flex;justify-content:space-between;align-items:center;margin-bottom:23px}.top h1{font-size:22px;margin:0;font-weight:650}.top p{font-size:12px;color:#9aa6b5;margin-top:6px}.account{display:flex;align-items:center;gap:12px;color:#64748b;font-size:13px}.panel{border-radius:13px;border:1px solid #e9edf3;margin-bottom:17px;box-shadow:0 2px 10px rgba(27,49,79,.025)}.notice{margin-top:15px;border-radius:9px}@media(max-width:850px){.side{width:170px}.workspace{padding:18px}.top{align-items:flex-start;gap:10px}.account{flex-wrap:wrap}}
</style>
