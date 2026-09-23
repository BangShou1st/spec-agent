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
