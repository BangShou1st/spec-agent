import { createRouter, createWebHistory } from 'vue-router'
import ProjectsView from '@/views/ProjectsView.vue'
import WorkspaceView from '@/views/WorkspaceView.vue'
import SettingsLayout from '@/views/SettingsLayout.vue'
import ModelsSettingsView from '@/views/settings/ModelsSettingsView.vue'
import SkillsListView from '@/views/settings/SkillsListView.vue'
import SkillDetailView from '@/views/settings/SkillDetailView.vue'
import ConnectionsListView from '@/views/settings/ConnectionsListView.vue'
import ConnectionDetailView from '@/views/settings/ConnectionDetailView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/projects' },
    { path: '/projects', name: 'projects', component: ProjectsView },
    { path: '/projects/:projectId', name: 'workspace', component: WorkspaceView, props: true },
    {
      path: '/settings',
      component: SettingsLayout,
      children: [
        { path: '', redirect: '/settings/models' },
        { path: 'models', name: 'settings-models', component: ModelsSettingsView },
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
