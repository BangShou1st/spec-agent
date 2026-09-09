import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import BackLink from '@/components/BackLink.vue'

describe('BackLink', () => {
  it('renders an accessible arrow link with hit area', () => {
    const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
    const w = mount(BackLink, { props: { to: '/settings/skills', label: 'Skills', testId: 'back-to-skills' }, global: { plugins: [router] } })
    const link = w.get('[data-test="back-to-skills"]')
    expect(link.text()).toContain('Skills')
    expect(link.find('svg').exists()).toBe(true)
    expect(link.attributes('href')).toBe('/settings/skills')
  })
})
