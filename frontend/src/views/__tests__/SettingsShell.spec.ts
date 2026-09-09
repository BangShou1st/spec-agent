import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import SettingsLayout from '@/views/SettingsLayout.vue'
import ModelsSettingsView from '@/views/settings/ModelsSettingsView.vue'
import SkillsSettingsView from '@/views/settings/SkillsSettingsView.vue'
import ConnectionsSettingsView from '@/views/settings/ConnectionsSettingsView.vue'

function testRouter(initial = '/settings/models') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      {
        path: '/settings',
        component: SettingsLayout,
        children: [
          { path: '', redirect: '/settings/models' },
          { path: 'models', component: ModelsSettingsView },
          { path: 'skills', component: SkillsSettingsView },
          { path: 'skills/:skillId', component: SkillsSettingsView },
          { path: 'connections', component: ConnectionsSettingsView },
          { path: 'connections/:connectionId', component: ConnectionsSettingsView },
        ],
      },
    ],
  })
  router.push(initial)
  return router
}

describe('settings shell navigation', () => {
  it('renders section nav with models, skills, and connections', async () => {
    const router = testRouter()
    await router.isReady()
    const wrapper = mount(SettingsLayout, { global: { plugins: [router, createPinia()] } })
    await router.isReady()
    expect(wrapper.get('[data-test="settings-nav-models"]'))
    expect(wrapper.get('[data-test="settings-nav-skills"]'))
    expect(wrapper.get('[data-test="settings-nav-connections"]'))
  })

  it('renders skills and connections placeholders without final UI', async () => {
    const skillsRouter = testRouter('/settings/skills')
    await skillsRouter.isReady()
    const skills = mount(SkillsSettingsView, { global: { plugins: [skillsRouter] } })
    expect(skills.get('[data-test="skills-settings"]'))
    expect(skills.get('[data-test="skills-placeholder"]'))
    const connsRouter = testRouter('/settings/connections')
    await connsRouter.isReady()
    const conns = mount(ConnectionsSettingsView, { global: { plugins: [connsRouter] } })
    expect(conns.get('[data-test="connections-settings"]'))
    expect(conns.get('[data-test="connections-placeholder"]'))
  })
})
