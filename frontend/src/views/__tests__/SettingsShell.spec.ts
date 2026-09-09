import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import SettingsLayout from '@/views/SettingsLayout.vue'
import SkillsListView from '@/views/settings/SkillsListView.vue'
import ConnectionsListView from '@/views/settings/ConnectionsListView.vue'

vi.mock('@/api/modelSettings', () => ({
  getOpenCodeSettings: vi.fn().mockResolvedValue({ configured: false, maskedKey: null, selectedModel: null }),
  listOpenCodeModels: vi.fn().mockResolvedValue({ freeModels: [] }),
  probeOpenCode: vi.fn(),
  saveOpenCode: vi.fn(),
  saveOpenCodeModel: vi.fn(),
}))

vi.mock('@/api/skills', () => ({
  listSkills: vi.fn().mockResolvedValue([]),
  getSkill: vi.fn(),
  listSkillVersions: vi.fn().mockResolvedValue([]),
  listSkillResources: vi.fn().mockResolvedValue([]),
  readSkillResource: vi.fn(),
  stageSkillZip: vi.fn(),
  stageSkillGit: vi.fn(),
  listStagedImports: vi.fn().mockResolvedValue([]),
  getStagedImport: vi.fn(),
  installStagedImport: vi.fn(),
  rejectStagedImport: vi.fn(),
  deleteStagedImport: vi.fn(),
  enableSkill: vi.fn(),
  disableSkill: vi.fn(),
  deleteSkill: vi.fn(),
}))

vi.mock('@/api/connections', () => ({
  listConnections: vi.fn().mockResolvedValue([]),
  getConnection: vi.fn(),
  createConnection: vi.fn(),
  updateConnection: vi.fn(),
  testConnection: vi.fn(),
  connectConnection: vi.fn(),
  refreshConnection: vi.fn(),
  enableConnection: vi.fn(),
  disableConnection: vi.fn(),
  deleteConnection: vi.fn(),
  listConnectionTools: vi.fn().mockResolvedValue([]),
  listConnectionResources: vi.fn().mockResolvedValue([]),
  listConnectionPrompts: vi.fn().mockResolvedValue([]),
  readConnectionResource: vi.fn(),
}))

async function shellAt(path: string) {
  const { default: ModelsSettingsView } = await import('@/views/settings/ModelsSettingsView.vue')
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      {
        path: '/settings',
        component: SettingsLayout,
        children: [
          { path: '', redirect: '/settings/models' },
          { path: 'models', component: ModelsSettingsView },
          { path: 'skills', component: SkillsListView },
          { path: 'connections', component: ConnectionsListView },
        ],
      },
    ],
  })
  setActivePinia(createPinia())
  router.push(path)
  await router.isReady()
  const wrapper = mount(SettingsLayout, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('settings shell navigation', () => {
  it('renders section nav with models, skills, and connections', async () => {
    const wrapper = await shellAt('/settings/models')
    expect(wrapper.get('[data-test="settings-nav-models"]'))
    expect(wrapper.get('[data-test="settings-nav-skills"]'))
    expect(wrapper.get('[data-test="settings-nav-connections"]'))
  })

  it('renders the skills management page with an add action', async () => {
    const wrapper = await shellAt('/settings/skills')
    expect(wrapper.get('[data-test="skills-page"]'))
    expect(wrapper.get('[data-test="add-skill"]'))
  })

  it('renders the connections management page with a create action', async () => {
    const wrapper = await shellAt('/settings/connections')
    expect(wrapper.get('[data-test="connections-page"]'))
    expect(wrapper.get('[data-test="add-connection"]'))
  })
})
