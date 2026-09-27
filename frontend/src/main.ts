// 文件名:main.ts
// 用途:前端应用入口:创建 Vue 应用与 Pinia,挂载根组件 App 并注册路由,同时引入 Vue Flow 基础样式与全局样式。
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import App from './App.vue'
import router from '@/app/router/index'
import '@/app/styles/style.css'

import '@/app/styles/providerSettings.css'
import '@/app/styles/mgmt.css'

createApp(App).use(createPinia()).use(router).mount('#app')
