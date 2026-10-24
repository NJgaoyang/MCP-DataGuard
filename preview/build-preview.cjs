const fs = require('node:fs');
const path = require('node:path');
const base = path.resolve(__dirname, '..');
const iconRoot = path.join(base, 'dataguard-web/node_modules/@ant-design/icons-svg/lib/asn');
const escape = s => String(s).replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;');
function element(node, root=false) {
 const attrs = {...node.attrs};
 if (root) Object.assign(attrs,{class:'icon',width:'18',height:'18',fill:'currentColor','aria-hidden':'true'});
 return '<'+node.tag+' '+Object.entries(attrs).map(([k,v])=>k+'="'+escape(v)+'"').join(' ')+'>'+(node.children||[]).map(n=>element(n)).join('')+'</'+node.tag+'>';
}
const icon = name => element(require(path.join(iconRoot,name+'.js')).default.icon,true);
let html = fs.readFileSync(path.join(__dirname,'dataguard-dashboard.template.html'),'utf8');
html = html.replaceAll('@@LOGO@@','data:image/svg+xml;base64,'+fs.readFileSync(path.join(base,'dataguard-web/public/dataguard-mark.svg')).toString('base64'));
html = html.replaceAll('@@END@@',icon('RightOutlined').replace('class="icon"','class="icon end"'));
html = html.replaceAll('@@STARS@@',Array.from({length:5},()=>icon('StarFilled')).join(''));
html = html.replace(/@@ICON_(\w+)@@/g,(_,name)=>icon(name));
html = html.replaceAll("['#12a594','#35b9a9','#6dd0c2','#a9e9dd','#d3f4eb']","['#12a594','#53b9ab','#83cdc1','#a1ded2','#ccf3ea']");
if (/@@\w+@@/.test(html)) throw new Error('Unresolved preview placeholders');
fs.writeFileSync(path.join(__dirname,'dataguard-dashboard.html'),html);
const js = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new (require('node:vm').Script)(js);
console.log('HTML generated; embedded JavaScript syntax valid; '+Buffer.byteLength(html)+' bytes');
