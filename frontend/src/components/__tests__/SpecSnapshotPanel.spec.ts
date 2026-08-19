import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SpecSnapshotPanel from '@/components/SpecSnapshotPanel.vue'
import { makeSpecSnapshot } from '@/test/fixtures'

function mountPanel(overrides: Partial<{
  routeId: string | null
  activeRouteId: string | null
  routeLabels: Record<string, string>
  snapshots: ReturnType<typeof makeSpecSnapshot>[]
  selectedSpecId: string | null
  generating: boolean
  commandPending: boolean
}> = {}) {
  return mount(SpecSnapshotPanel, {
    props: {
      routeId: 'r1',
      activeRouteId: 'r1',
      snapshots: [],
      selectedSpecId: null,
      generating: false,
      commandPending: false,
      routeLabels: { r1: '主路线', rA: '当前路线', rB: '开放分支' },
      ...overrides,
    },
  })
}

describe('SpecSnapshotPanel', () => {
  it('shows the active-route generation target', () => {
    const wrapper = mountPanel()
    expect(wrapper.find('[data-test="generate-spec"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('为当前路线生成规格')
    expect(wrapper.text()).toContain('当前路线：主路线')
  })

  it('warns when reading a different route than the active one', () => {
    const wrapper = mountPanel({ routeId: 'rB', activeRouteId: 'rA' })
    expect(wrapper.find('[data-test="generate-focus-warning"]').text()).toContain('开放分支')
    expect(wrapper.find('[data-test="generate-focus-warning"]').text()).toContain('当前路线')
  })

  it('emits generate intent and disables while generating', async () => {
    const wrapper = mountPanel()
    await wrapper.find('[data-test="generate-spec"]').trigger('click')
    expect(wrapper.emitted('generate-spec')).toHaveLength(1)
    await wrapper.setProps({ generating: true })
    expect(wrapper.find('[data-test="generate-spec"]').attributes('disabled')).toBeDefined()
  })

  it('lists snapshots and emits selection', async () => {
    const snapshots = [
      makeSpecSnapshot({ id: 'spec-1', createdAt: '2026-01-02T00:00:00Z' }),
      makeSpecSnapshot({ id: 'spec-2', createdAt: '2026-01-01T00:00:00Z' }),
    ]
    const wrapper = mountPanel({ snapshots })
    const items = wrapper.findAll('[data-test="spec-snapshot-item"]')
    expect(items).toHaveLength(2)
    await items[0].trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['spec-1'])
  })

  it('renders the derived spec detail with sections, unresolved items and source refs verbatim', () => {
    const snapshot = makeSpecSnapshot({
      id: 'spec-1',
      sections: [{ id: 's1', title: 'Overview', content: 'Original English spec body.' }],
      unresolvedItems: [{ text: 'An open aspect.', category: 'unresolved' }],
      sourceRefs: [{ kind: 'node', refId: 'n1' }, { kind: 'node', refId: 'n1' }],
    })
    const wrapper = mountPanel({ snapshots: [snapshot], selectedSpecId: 'spec-1' })
    expect(wrapper.find('[data-test="spec-snapshot-detail"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="derived-label"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Original English spec body.')
    expect(wrapper.text()).toContain('An open aspect.')
    expect(wrapper.findAll('[data-test="source-reference"]')).toHaveLength(1)
    expect(wrapper.find('[data-test="source-reference"]').text()).toContain('node：n1')
  })

  it('shows an empty state when no snapshots exist', () => {
    const wrapper = mountPanel()
    expect(wrapper.find('[data-test="specs-empty"]').exists()).toBe(true)
  })

  it('disables generation without an active route', () => {
    const wrapper = mountPanel({ activeRouteId: null })
    expect(wrapper.find('[data-test="generate-spec"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('没有当前路线')
  })
})
