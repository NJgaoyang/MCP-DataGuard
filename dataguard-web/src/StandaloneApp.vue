<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue';
import type {FormInstance} from 'ant-design-vue';
import {api,initializeSession,logout,roleLabel,strongPasswordRule,useActions,type CurrentUser} from './standalone/api';
import AppIcon from './standalone/AppIcon.vue';
import OverviewView from './standalone/OverviewView.vue';
import SourcesView from './standalone/SourcesView.vue';
import ApprovalsView from './standalone/ApprovalsView.vue';
import AgentsView from './standalone/AgentsView.vue';
import UsersView from './standalone/UsersView.vue';
import PoliciesView from './standalone/PoliciesView.vue';
import WorkflowsView from './standalone/WorkflowsView.vue';
import AuditView from './standalone/AuditView.vue';
import PagePanel from './standalone/PagePanel.vue';
import './styles/standalone.css';
const theme={token:{colorPrimary:'#2f75ff',colorPrimaryHover:'#448bff',colorPrimaryActive:'#2864d9',colorPrimaryBg:'#eaf2ff',colorSuccess:'#20bf82',colorWarning:'#ab6400',colorError:'#e5484d',colorInfo:'#2f75ff',colorText:'#1e2b4d',colorTextSecondary:'#65799f',colorTextTertiary:'#899bb7',colorBorder:'#e5edf8',colorSplit:'#e5edf8',colorBgContainer:'#ffffff',colorBgLayout:'#f3f7ff',borderRadius:10,fontSize:14,fontSizeSM:12,fontSizeLG:14,fontSizeXL:20,fontSizeHeading1:28,fontSizeHeading2:20,fontSizeHeading3:20,fontSizeHeading4:14,fontSizeHeading5:14,lineWidth:0.5,motion:false},components:{Table:{headerBg:'#f7faff',headerColor:'#65799f',rowHoverBg:'#f4f8ff',cellPaddingBlock:18,cellPaddingInline:20},Modal:{titleFontSize:20},Menu:{itemSelectedBg:'#eaf2ff',itemSelectedColor:'#2864d9'}}};
const me=ref<CurrentUser|null>(null),starting=ref(true),startupError=ref(''),tab=ref('dashboard'),mobileOpen=ref(false),analyticsOpen=ref(true),activeView=ref<{refresh?:()=>Promise<void>}|null>(null),refreshing=ref(false),passwordOpen=ref(false),passwordRef=ref<FormInstance>(),password=ref({currentPassword:'',newPassword:''});
const {busy,perform}=useActions();
const admin=computed(()=>me.value?.roleCode==='ADMIN');
const titles:Record<string,string>={dashboard:'安全总览',sources:'数据源管理',agents:'Agent 管理',policies:'SQL 安全规则',workflows:'审批流程',approvals:'审批中心',audit:'SQL 审计',alerts:'安全告警',users:'用户与权限',account:'账户设置'};
const topMenus=computed(()=>[{id:'agents',title:'Agent 管理'},...(admin.value?[{id:'sources',title:'数据源管理'},{id:'policies',title:'SQL 安全规则'},{id:'workflows',title:'审批流程'}]:[]),{id:'approvals',title:'审批中心'}]);
const title=computed(()=>me.value?.mustChangePassword?'首次登录安全设置':titles[tab.value]||'数据概览');
const group=computed(()=>['dashboard','audit','alerts'].includes(tab.value)?'数据管理':'工作台');
function navigate(page:string){if(!me.value||me.value.mustChangePassword)return;if(['sources','policies','workflows','users','audit','alerts'].includes(page)&&!admin.value)return;tab.value=page;mobileOpen.value=false;if(['dashboard','audit','alerts'].includes(page))analyticsOpen.value=true;window.scrollTo({top:0,behavior:'auto'})}
async function start(){starting.value=true;startupError.value='';try{me.value=await initializeSession();passwordOpen.value=Boolean(me.value.mustChangePassword)}catch(e){startupError.value=(e as Error).message}finally{starting.value=false}}
async function refresh(){if(refreshing.value)return;refreshing.value=true;try{if(tab.value==='account')me.value=await api('/api/me');else await activeView.value?.refresh?.()}finally{refreshing.value=false}}
function openPassword(){password.value={currentPassword:'',newPassword:''};passwordOpen.value=true}
async function changePassword(){const result=await perform('/api/me/password',password.value,'POST','密码已修改，请重新登录');if(result?.reauthenticate){password.value={currentPassword:'',newPassword:''};location.assign('/login.html')}}
function passwordRequired(){if(me.value){me.value.mustChangePassword=true;openPassword()}}
function accountAction(event:{key:string|number}){if(event.key==='password')openPassword();if(event.key==='account')navigate('account');if(event.key==='logout')logout()}
function closeOnEscape(event:KeyboardEvent){if(event.key==='Escape')mobileOpen.value=false}
watch(passwordOpen,open=>{if(!open){password.value={currentPassword:'',newPassword:''};passwordRef.value?.clearValidate()}});
onMounted(()=>{void start();window.addEventListener('dataguard-password-required',passwordRequired);window.addEventListener('keydown',closeOnEscape)});
onBeforeUnmount(()=>{window.removeEventListener('dataguard-password-required',passwordRequired);window.removeEventListener('keydown',closeOnEscape)});
</script>
<template>
<a-config-provider :theme="theme"><div class="dg-app">
 <button v-if="mobileOpen" class="dg-nav-mask" aria-label="关闭导航" @click="mobileOpen=false"/>
 <aside class="dg-sidebar" :class="{'is-open':mobileOpen}" aria-label="主导航">
  <button class="dg-brand" @click="navigate('dashboard')"><img src="/dataguard-mark.svg" alt=""/><span><strong>MCP-DataGuard</strong><small>数据安全 · 可控访问</small></span></button>
  <div class="dg-nav-label">管理平台</div>
  <nav class="dg-navigation">
   <button :disabled="!me||me.mustChangePassword" @click="navigate('dashboard')"><AppIcon name="dashboard"/><span>安全总览</span><AppIcon name="right" class="dg-nav-chevron"/></button>
   <button v-for="item in topMenus" :key="item.id" :class="{active:tab===item.id}" :aria-current="tab===item.id?'page':undefined" :disabled="!me||me.mustChangePassword" @click="navigate(item.id)"><AppIcon :name="item.id"/><span>{{item.title}}</span><AppIcon name="right" class="dg-nav-chevron"/></button>
   <button class="dg-nav-group" :aria-expanded="analyticsOpen" @click="analyticsOpen=!analyticsOpen"><AppIcon name="audit"/><span>数据管理</span><AppIcon name="down" class="dg-nav-chevron" :class="{rotated:analyticsOpen}"/></button>
   <template v-if="analyticsOpen"><button class="dg-nav-child" :class="{active:tab==='dashboard'}" :aria-current="tab==='dashboard'?'page':undefined" :disabled="!me||me.mustChangePassword" @click="navigate('dashboard')">数据概览</button><button v-if="admin" class="dg-nav-child" :class="{active:tab==='audit'}" :aria-current="tab==='audit'?'page':undefined" :disabled="me?.mustChangePassword" @click="navigate('audit')">SQL 审计</button><button v-if="admin" class="dg-nav-child" :class="{active:tab==='alerts'}" :aria-current="tab==='alerts'?'page':undefined" :disabled="me?.mustChangePassword" @click="navigate('alerts')">安全告警</button></template>
   <button v-if="admin" :class="{active:tab==='users'}" :aria-current="tab==='users'?'page':undefined" :disabled="me?.mustChangePassword" @click="navigate('users')"><AppIcon name="users"/><span>用户与权限</span><AppIcon name="right" class="dg-nav-chevron"/></button>
   <button :class="{active:tab==='account'}" :disabled="!me||me.mustChangePassword" @click="navigate('account')"><AppIcon name="settings"/><span>账户设置</span><AppIcon name="right" class="dg-nav-chevron"/></button>
  </nav>
  <div class="dg-sidebar-foot"><span>MySQL 单体版</span><span>DataGuard</span></div>
 </aside>
 <header class="dg-topbar"><div class="dg-breadcrumb"><button class="dg-icon-button dg-mobile-toggle" aria-label="打开导航" :aria-expanded="mobileOpen" @click="mobileOpen=!mobileOpen"><AppIcon name="menu"/></button><span>{{group}}</span><span>/</span><strong>{{title}}</strong></div><div class="dg-top-actions"><a-tooltip :title="admin?'安全告警':'审批中心'"><button class="dg-icon-button" :disabled="!me||me.mustChangePassword" :aria-label="admin?'查看安全告警':'查看审批中心'" @click="navigate(admin?'alerts':'approvals')"><AppIcon name="alerts"/></button></a-tooltip><span class="dg-top-divider"/><a-dropdown :trigger="['click']"><button class="dg-account-button" aria-label="账户菜单"><span class="dg-user-avatar">{{String(me?.displayName||me?.username||'管').slice(0,1)}}</span><span>{{me?.displayName||me?.username||'账户'}}</span><AppIcon name="down"/></button><template #overlay><a-menu @click="accountAction"><a-menu-item key="role" disabled>{{roleLabel(me?.roleCode||'')}}</a-menu-item><a-menu-divider/><a-menu-item key="account" :disabled="me?.mustChangePassword"><AppIcon name="settings"/> 账户设置</a-menu-item><a-menu-item key="password"><AppIcon name="password"/> 修改密码</a-menu-item><a-menu-item key="logout"><AppIcon name="logout"/> 退出登录</a-menu-item></a-menu></template></a-dropdown></div></header>
 <main class="dg-main"><div class="dg-page-head"><h1>{{title}}</h1><a-button v-if="me&&!me.mustChangePassword" :loading="refreshing" @click="refresh"><AppIcon name="refresh"/>刷新</a-button></div>
  <div v-if="starting" class="dg-startup"><a-spin tip="正在加载"/></div>
  <a-alert v-else-if="startupError" :message="startupError" type="error" show-icon><template #action><a-button @click="start">重试</a-button></template></a-alert>
  <template v-else-if="me&&!me.mustChangePassword">
   <OverviewView v-if="tab==='dashboard'" ref="activeView" @navigate="navigate"/>
   <SourcesView v-else-if="tab==='sources'&&admin" ref="activeView"/>
   <ApprovalsView v-else-if="tab==='approvals'" ref="activeView" :user="me"/>
   <AgentsView v-else-if="tab==='agents'" ref="activeView"/>
   <UsersView v-else-if="tab==='users'&&admin" ref="activeView" :user="me"/>
   <PoliciesView v-else-if="tab==='policies'&&admin" ref="activeView"/>
   <WorkflowsView v-else-if="tab==='workflows'&&admin" ref="activeView"/>
   <AuditView v-else-if="(tab==='audit'||tab==='alerts')&&admin" ref="activeView" :mode="tab==='alerts'?'alerts':'audit'"/>
   <PagePanel v-else-if="tab==='account'" title="账户信息"><template #actions><a-button type="primary" @click="openPassword">修改密码</a-button></template><div class="dg-account-details"><a-descriptions :column="1" layout="vertical"><a-descriptions-item label="姓名">{{me.displayName}}</a-descriptions-item><a-descriptions-item label="账号">{{me.username}}</a-descriptions-item><a-descriptions-item label="角色">{{roleLabel(me.roleCode)}}</a-descriptions-item></a-descriptions></div></PagePanel>
  </template>
  <footer class="dg-footer"><span>DataGuard 数据守卫</span><span>数据安全访问管理平台</span></footer>
 </main>
 <a-modal v-model:open="passwordOpen" :title="me?.mustChangePassword?'首次登录修改密码':'修改密码'" centered :width="520" :closable="!me?.mustChangePassword&&!busy" :mask-closable="!me?.mustChangePassword&&!busy" :keyboard="!me?.mustChangePassword" :footer="null"><p class="dg-field-note">密码为 12–72 位，包含大写、小写字母、数字和特殊字符。</p><a-form ref="passwordRef" :model="password" layout="vertical" @finish="changePassword"><a-form-item label="当前密码" name="currentPassword" :rules="[{required:true,message:'请输入当前密码'}]"><a-input-password v-model:value="password.currentPassword" autocomplete="current-password"/></a-form-item><a-form-item label="新密码" name="newPassword" :rules="[strongPasswordRule]"><a-input-password v-model:value="password.newPassword" autocomplete="new-password"/></a-form-item><div class="dg-modal-footer"><a-button v-if="!me?.mustChangePassword" :disabled="busy" @click="passwordOpen=false">取消</a-button><a-button v-else :disabled="busy" @click="logout">退出登录</a-button><a-button type="primary" html-type="submit" :loading="busy">修改并重新登录</a-button></div></a-form></a-modal>
</div></a-config-provider>
</template>
