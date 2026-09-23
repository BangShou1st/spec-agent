import { createRouter, createWebHistory } from 'vue-router'
import ProjectsView from '@/features/projects/ProjectsView.vue'
import WorkspaceView from '@/features/workspace/WorkspaceView.vue'
import SettingsLayout from '@/app/layouts/SettingsLayout.vue'
import ModelSettingsView from '@/features/model-settings/ModelSettingsView.vue'
import SkillsListView from '@/features/skills/SkillsListView.vue'
import SkillDetailView from '@/features/skills/SkillDetailView.vue'
import ConnectionsListView from '@/features/connections/ConnectionsListView.vue'
import ConnectionDetailView from '@/features/connections/ConnectionDetailView.vue'

const router = createRouter({
  history: createWebHistory(),
  // 设置三个子页高度不同，切换时若保留旧滚动位置，页面会“弹来弹去”。
  // 子路由切换一律回到顶部；历史导航沿用浏览器保存的位置。
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.path.startsWith('/settings/') && from.path.startsWith('/settings/')) {
      return { top: 0 }
    }
    return {}
  },
  routes: [
    { path: '/', redirect: '/projects' },
    { path: '/projects', name: 'projects', component: ProjectsView },
    { path: '/projects/:projectId', name: 'workspace', component: WorkspaceView, props: true },
    {
      path: '/settings',
      component: SettingsLayout,
      children: [
        { path: '', redirect: '/settings/models' },
        { path: 'models', name: 'settings-models', component: ModelSettingsView },
        { path: 'skills', name: 'settings-skills', component: SkillsListView },
        { path: 'skills/:skillId', name: 'settings-skill-detail', component: SkillDetailView, props: true },
        { path: 'connections', name: 'settings-connections', component: ConnectionsListView },
        {
          path: 'connections/:connectionId',
          name: 'settings-connection-detail',
          component: ConnectionDetailView,
          props: true,
        },
      ],
    },
  ],
})

export default router
