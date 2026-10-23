import {createApp,h} from 'vue';
import Antd,{ConfigProvider} from 'ant-design-vue';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import 'ant-design-vue/dist/reset.css';
import './styles/console-polish.css';
import App from './App.vue';

// One provider for every page, dialog, confirmation and pagination control.
const app=createApp({
 render:()=>h(ConfigProvider,{locale:zhCN},{default:()=>h(App)})
});
app.use(Antd);
app.mount('#app');
