const fs=require('node:fs');
const assert=require('node:assert/strict');
const {JSDOM,VirtualConsole}=require('jsdom');
const runtimeErrors=[];let downloads=0;
const virtualConsole=new VirtualConsole();
virtualConsole.on('jsdomError',error=>runtimeErrors.push(error.message));
const html=fs.readFileSync(__dirname+'/dataguard-ui.html','utf8');
const dom=new JSDOM(html,{runScripts:'dangerously',pretendToBeVisual:true,url:'http://localhost:8090/dataguard-ui.html',virtualConsole,beforeParse(window){
 window.scrollTo=()=>{};
 window.Element.prototype.scrollIntoView=()=>{};
 window.HTMLCanvasElement.prototype.getBoundingClientRect=function(){return {width:720,height:245,left:0,top:0,right:720,bottom:245}};
 window.HTMLCanvasElement.prototype.getContext=function(){return new Proxy({createLinearGradient:()=>({addColorStop(){}})},{get:(target,key)=>key in target?target[key]:()=>{},set:(target,key,value)=>{target[key]=value;return true}})};
 window.HTMLFormElement.prototype.requestSubmit=function(){if(this.checkValidity())this.dispatchEvent(new window.Event('submit',{bubbles:true,cancelable:true}))};
 window.URL.createObjectURL=()=> 'blob:preview-test';window.URL.revokeObjectURL=()=>{};
 window.HTMLAnchorElement.prototype.click=function(){if(this.download)downloads++};
}});
const window=dom.window,document=window.document,$=id=>document.getElementById(id),click=selector=>{const node=document.querySelector(selector);assert.ok(node,selector);node.click()},input=(id,value)=>{$(id).value=value;$(id).dispatchEvent(new window.Event('input',{bubbles:true}))};
function fill(values){for(const [name,value]of Object.entries(values)){const field=document.querySelector('#preview-form [name="'+name+'"]');assert.ok(field,name);field.value=value}document.querySelector('.dialog-actions button:last-child').click()}
async function check(){
 await new Promise(resolve=>setTimeout(resolve,35));
 assert.equal(document.querySelectorAll('.screen').length,8);
 assert.equal(document.querySelectorAll('.nav .child.active').length,1);
 const ids=Array.from(document.querySelectorAll('[id]'),n=>n.id);assert.equal(ids.length,new Set(ids).size,'duplicate IDs');
 click('[data-dialog="数据源管理"]');assert.equal($('screen-sources').hidden,false);assert.equal($('screen-overview').hidden,true);assert.equal($('sources-body').children.length,8);
 input('source-search','客户');assert.equal($('sources-body').children.length,2);input('source-search','');
 click('#new-source');fill({name:'预览测试库',code:'preview_db',host:'10.20.3.12',port:'3306',db:'preview',username:'preview_reader',password:'preview-only-password',owner:'管理员'});
 assert.equal($('source-count').textContent,'9');assert.equal($('dialog-backdrop').classList.contains('open'),false);assert.equal(document.querySelector('#preview-form input[type="password"]').value,'');
 click('#new-source');fill({name:'重复编码',code:'preview_db',host:'10.20.3.12',port:'3306',db:'preview',username:'preview_reader',password:'preview-only-password',owner:'管理员'});assert.equal($('source-count').textContent,'9');assert.equal($('dialog-backdrop').classList.contains('open'),true);click('#close-dialog');
 click('[data-dialog="Agent 管理"]');assert.equal($('agents-body').children.length,8);click('[data-action="agent-toggle"][data-id="1"]');assert.equal($('agent-running').textContent,'5');
 click('[data-dialog="SQL 安全规则"]');click('[data-action="rule-toggle"][data-id="5"]');assert.equal($('rule-active').textContent,'5');assert.equal($('rule-drafts').textContent,'1');
 click('.nav [data-target="approvals"]');assert.equal($('approvals-body').children.length,3);click('[data-action="approval-detail"][data-id="1042"]');document.querySelector('.dialog-actions button:last-child').click();assert.equal($('pending-count').textContent,'3');$('approval-comment').value='已核对访问范围';document.querySelector('.dialog-actions button:last-child').click();assert.equal($('pending-count').textContent,'2');assert.equal($('handled-count').textContent,'13');
 click('[data-approval-tab="processed"]');assert.ok($('approvals-body').textContent.includes('订单分析数据访问'));
 click('.nav [data-target="requests-panel"]');assert.equal($('screen-audit').hidden,false);input('audit-search','REQ-20261024-1842');assert.equal($('audit-body').children.length,1);click('[data-action="audit-detail"]');assert.ok($('dialog-content').textContent.includes('REQ-20261024-1842'));window.document.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Escape',bubbles:true}));assert.equal($('dialog-backdrop').classList.contains('open'),false);
 click('#export-audit');assert.equal(downloads,1);
 click('[data-dialog="用户与权限"]');input('user-search','张林');assert.equal($('users-body').children.length,1);
 click('[data-dialog="系统设置"]');$('settings-form').dispatchEvent(new window.Event('submit',{bubbles:true,cancelable:true}));
 click('.nav .child[data-target="overview"]');assert.equal($('screen-overview').hidden,false);click('[data-days="7"]');assert.ok($('date-label').textContent.includes('18日'));click('[data-env="test"]');assert.equal($('agents-total').textContent,'2');click('#export-button');assert.equal(downloads,2);
 await new Promise(resolve=>setTimeout(resolve,30));assert.deepEqual(runtimeErrors,[]);
 console.log('PASS: navigation, unique IDs, filtering, source form, duplicate validation, password clearing, Agent status, rule publishing, approval decisions, audit detail, Escape, CSV, users, settings and overview filters.');
 console.log('DOM logic only; canvas is mocked. This check does not verify visual layout or real browser rendering.');
 dom.window.close();
}
check().catch(error=>{console.error(error);dom.window.close();process.exitCode=1});
