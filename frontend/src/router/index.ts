import { createRouter, createWebHistory } from 'vue-router'
import ProjectsView from '@/views/ProjectsView.vue'
import WorkspaceView from '@/views/WorkspaceView.vue'
import SettingsLayout from '@/views/SettingsLayout.vue'
import ModelsSettingsView from '@/views/settings/ModelsSettingsView.vue'
import SkillsSettingsView from '@/views/settings/SkillsSettingsView.vue'
import ConnectionsSettingsView from '@/views/settings/ConnectionsSettingsView.vue'

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
        { path: 'skills', name: 'settings-skills', component: SkillsSettingsView },
        { path: 'skills/:skillId', name: 'settings-skill-detail', component: SkillsSettingsView, props: true },
        { path: 'connections', name: 'settings-connections', component: ConnectionsSettingsView },
        {
          path: 'connections/:connectionId',
          name: 'settings-connection-detail',
          component: ConnectionsSettingsView,
          props: true,
        },
      ],
    },
  ],
})

export default router
