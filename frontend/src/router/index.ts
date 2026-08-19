import { createRouter, createWebHistory } from 'vue-router'
import ProjectsView from '@/views/ProjectsView.vue'
import WorkspaceView from '@/views/WorkspaceView.vue'
import SettingsView from '@/views/SettingsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/projects' },
    { path: '/projects', name: 'projects', component: ProjectsView },
    { path: '/projects/:projectId', name: 'workspace', component: WorkspaceView, props: true },
    { path: '/settings', name: 'settings', component: SettingsView },
  ],
})

export default router
