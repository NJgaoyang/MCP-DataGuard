<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import {api,showError,type Row} from './api';
import PagePanel from './PagePanel.vue';
import AppIcon from './AppIcon.vue';

type ReadResult={decision:string;table:string;rows:Row[];rowCount:number;masking:string};
const sources=ref<Row[]>([]),tables=ref<Row[]>([]),columns=ref<Row[]>([]);
const sourceId=ref(''),tableName=ref(''),limit=ref(10);
const result=ref<ReadResult|null>(null),loading=ref(false),reading=ref(false),error=ref('');
let requestSeq=0;
const sourceOptions=computed(()=>sources.value.filter(r=>Boolean(r.enabled)).map(r=>({
 value:String(r.id),label:`${r.displayName} (${r.dbType==='STARROCKS'?'StarRocks':'MySQL'})`
})));
const tableOptions=computed(()=>tables.value.filter(r=>Boolean(r.enabled)).map(r=>({
 value:String(r.tableName),label:String(r.tableName)
})));
const approvedColumns=computed(()=>columns.value.filter(r=>Boolean(r.enabled)).map(r=>String(r.columnName)));
const preview=computed(()=>{
 if(!tableName.value)return '-- 选择已授权的数据表后自动生成安全 SELECT';
 if(approvedColumns.value.length===0)return '-- 当前表没有允许披露的字段，拒绝执行';
 return 'SELECT '+approvedColumns.value.map(c=>'`'+c+'`').join(', ')
  +' FROM `'+tableName.value+'` LIMIT '+limit.value+';';
});
const dataColumns=computed(()=>Object.keys(result.value?.rows?.[0]||{}).map(key=>({title:key,dataIndex:key,key,ellipsis:true})));
const canExecute=computed(()=>sourceId.value&&tableName.value&&approvedColumns.value.length>0
 &&Number.isInteger(limit.value)&&limit.value>=1&&limit.value<=100&&!reading.value);

async function refresh(){
 const turn=++requestSeq;loading.value=true;error.value='';result.value=null;
 try{
  const rows=await api<Row[]>('/api/sources');
  if(turn!==requestSeq)return;
  sources.value=rows.filter(x=>Boolean(x.enabled));
  if(!sources.value.some(x=>x.id===sourceId.value))sourceId.value='';
 }catch(e){if(turn===requestSeq){error.value=(e as Error).message;showError(e)}}
 finally{if(turn===requestSeq)loading.value=false}
}
watch(sourceId,async id=>{
 const turn=++requestSeq;tableName.value='';columns.value=[];tables.value=[];result.value=null;
 if(!id)return;
 try{
  const rows=await api<Row[]>('/api/sources/'+encodeURIComponent(id)+'/allowlist');
  if(turn===requestSeq)tables.value=rows.filter(r=>Boolean(r.enabled));
 }catch(e){if(turn===requestSeq)showError(e)}
});
watch(tableName,async name=>{
 const turn=++requestSeq;columns.value=[];result.value=null;
 if(!name||!sourceId.value)return;
 try{
  const rows=await api<Row[]>('/api/sources/'+encodeURIComponent(sourceId.value)+'/allowlist/'+encodeURIComponent(name)+'/columns');
  if(turn===requestSeq)columns.value=rows.filter(r=>Boolean(r.enabled));
 }catch(e){if(turn===requestSeq)showError(e)}
});
async function execute(){
 if(!canExecute.value)return;
 reading.value=true;result.value=null;error.value='';
 try{
  // This is NOT an arbitrary SQL editor. The backend independently rebuilds
  // the column projection, verifies owner approval and target read-only grants.
  result.value=await api<ReadResult>('/api/read','POST',{
   sourceId:sourceId.value,tableName:tableName.value,limit:limit.value
  });
 }catch(e){error.value=(e as Error).message;showError(e)}
 finally{reading.value=false}
}
onMounted(refresh);defineExpose({refresh});
</script>
<template>
 <PagePanel title="SQL 安全查询">
  <template #actions><a-button @click="refresh" :loading="loading"><AppIcon name="refresh"/>刷新授权</a-button></template>
  <a-alert type="info" show-icon message="固定单表 SELECT 模式：读取必须同时满足表白名单、字段授权和有效的 Flowable 审批。暂不支持执行任意 SQL。"/>
  <div class="dg-v2-read-controls">
   <a-form layout="vertical">
    <div class="dg-form-grid">
     <a-form-item label="业务数据源">
      <a-select v-model:value="sourceId" :options="sourceOptions" placeholder="选择已启用数据源" show-search option-filter-prop="label" allow-clear/>
     </a-form-item>
     <a-form-item label="白名单数据表">
      <a-select v-model:value="tableName" :options="tableOptions" placeholder="选择已授权数据表" :disabled="!sourceId" show-search option-filter-prop="label" allow-clear/>
     </a-form-item>
     <a-form-item label="返回行数（最大 100）">
      <a-input-number v-model:value="limit" :min="1" :max="100" :precision="0" style="width:100%"/>
     </a-form-item>
     <a-form-item label="允许披露的字段">
      <div class="dg-v2-read-columns">{{approvedColumns.length?approvedColumns.join(' · '):'暂无字段授权'}}</div>
     </a-form-item>
    </div>
   </a-form>
  </div>
  <div class="dg-v2-read-heading"><strong>服务端限定的 SQL 形态预览</strong>
   <a-button type="primary" :loading="reading" :disabled="!canExecute" @click="execute">安全执行</a-button>
  </div>
  <pre class="dg-v2-read-preview">{{preview}}</pre>
  <a-alert v-if="error" show-icon type="warning" :message="error" class="dg-notice"/>
  <section v-if="result" class="dg-v2-read-results">
   <div class="dg-v2-read-heading"><strong>查询结果</strong><span>共 {{result.rowCount}} 行 · {{result.masking}}</span></div>
   <a-table :data-source="result.rows" :columns="dataColumns" :pagination="{pageSize:10}" :row-key="(_row:Row,index:number)=>index" size="small" :scroll="{x:true}"/>
  </section>
 </PagePanel>
</template>
