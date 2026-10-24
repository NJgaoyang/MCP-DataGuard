import type {FormInstance} from 'ant-design-vue';
import {ref} from 'vue';
import {Modal,message} from 'ant-design-vue';
export type Row=Record<string,any>;
export type CurrentUser={username:string;displayName:string;roleCode:string;mustChangePassword:boolean};
let csrf='';
export async function initializeSession(){const response=await fetch('/api/csrf',{credentials:'same-origin'});if(!response.ok)throw Error('登录凭证获取失败，请刷新页面');csrf=(await response.json()).token;return api<CurrentUser>('/api/me')}
export async function api<T=any>(path:string,method='GET',body?:unknown):Promise<T>{
 const response=await fetch(path,{method,credentials:'same-origin',headers:{'X-CSRF-TOKEN':csrf,...(body!==undefined?{'Content-Type':'application/json'}:{})},body:body===undefined?undefined:JSON.stringify(body)});
 if(response.status===401){location.assign('/login.html');throw Error('会话已失效，请重新登录')}
 if(response.status===428)window.dispatchEvent(new Event('dataguard-password-required'));
 if(!response.ok){let detail='';try{const data=await response.json();detail=data.message||data.detail||data.error||''}catch{}throw Error(detail||`请求失败（${response.status}）`)}
 return response.status===204?undefined as T:response.json();
}
export function showError(error:unknown){Modal.error({title:'操作失败',content:error instanceof Error?error.message:'暂时无法连接服务，请稍后重试',centered:true,okText:'关闭',class:'dg-modal'})}
export function useActions(){const busy=ref(false);async function perform<T=any>(path:string,body?:unknown,method='POST',success='操作成功'):Promise<T|null>{if(busy.value)return null;busy.value=true;try{const result=await api<T>(path,method,body);if(success)message.success(success);return result}catch(error){showError(error);return null}finally{busy.value=false}}return {busy,perform}}
export function logout(){const form=document.createElement('form');form.action='/auth/logout';form.method='post';const input=document.createElement('input');input.type='hidden';input.name='_csrf';input.value=csrf;form.append(input);document.body.append(form);form.submit()}
export function dateTime(value:unknown){if(value==null||value==='')return '—';const date=new Date(String(value));return Number.isNaN(date.getTime())?String(value):new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(date).replaceAll('/','-')}
export function roleLabel(value:string){return ({ADMIN:'管理员',APPROVER:'审批人员',READER:'读取人员'} as Row)[value]||value}
export function approvalLabel(value:string){return ({PENDING:'待审批',APPROVED:'已通过',REJECTED:'已拒绝',CANCELLED:'已撤回',EXPIRED:'已过期'} as Row)[value]||value}
export function outcomeLabel(value:string){return ({SUCCESS:'成功',DENIED:'已拒绝',ERROR:'失败',FAILED:'失败'} as Row)[value]||value}
export function matches(row:Row,query:string,keys:string[]){const term=query.trim().toLocaleLowerCase();return !term||keys.some(key=>String(row[key]??'').toLocaleLowerCase().includes(term))}
export function exportCsv(name:string,columns:{label:string;value:(row:Row)=>unknown}[],rows:Row[]){const quote=(value:unknown)=>{let text=String(value??'');if(/^[=+\-@\t\r\n]/.test(text))text="'"+text;return '"'+text.replaceAll('"','""')+'"'};const csv='\uFEFF'+[columns.map(c=>quote(c.label)).join(','),...rows.map(row=>columns.map(c=>quote(c.value(row))).join(','))].join('\r\n');const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));const anchor=document.createElement('a');anchor.href=url;anchor.download=name+'.csv';anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
export const pagination={pageSize:10,showSizeChanger:false,showTotal:(total:number)=>`共 ${total} 条`};
export function passwordError(value:string){if(!value)return '请输入密码';if(value.length<12||value.length>72)return '密码长度须为 12 到 72 位';if(!/[A-Z]/.test(value)||!/[a-z]/.test(value)||!/[0-9]/.test(value)||!(/[^A-Za-z0-9]/.test(value)))return '密码须包含大写、小写字母、数字和特殊字符';return ''}
export const strongPasswordRule={validator:(_:unknown,value:string)=>{const error=passwordError(value||'');return error?Promise.reject(error):Promise.resolve()}};

export async function submitForm(form:FormInstance|undefined,action:()=>Promise<void>){if(!form)return;try{await form.validateFields()}catch{return}await action()}
