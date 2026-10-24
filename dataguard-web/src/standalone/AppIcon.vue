<script setup lang="ts">
import {computed} from 'vue';
import {DashboardOutlined,DatabaseOutlined,RobotOutlined,SafetyCertificateOutlined,AuditOutlined,TeamOutlined,BarChartOutlined,BellOutlined,ReloadOutlined,LogoutOutlined,MenuOutlined,SearchOutlined,PlusOutlined,RightOutlined,DownOutlined,CloudDownloadOutlined,LockOutlined,FileTextOutlined,ClockCircleOutlined,SettingOutlined,CloseOutlined} from '@ant-design/icons-vue';
const props=defineProps<{name:string}>();
const icons:Record<string,any>={dashboard:DashboardOutlined,sources:DatabaseOutlined,agents:RobotOutlined,policies:SafetyCertificateOutlined,approvals:AuditOutlined,users:TeamOutlined,audit:BarChartOutlined,alerts:BellOutlined,refresh:ReloadOutlined,logout:LogoutOutlined,menu:MenuOutlined,search:SearchOutlined,plus:PlusOutlined,right:RightOutlined,down:DownOutlined,export:CloudDownloadOutlined,password:LockOutlined,workflows:FileTextOutlined,clock:ClockCircleOutlined,settings:SettingOutlined,close:CloseOutlined};
const icon=computed(()=>icons[props.name]||FileTextOutlined);
</script>
<template><component :is="icon" class="dg-icon" aria-hidden="true"/></template>
