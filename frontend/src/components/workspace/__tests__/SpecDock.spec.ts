import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SpecDock from '../SpecDock.vue'
import { makeSpecSnapshot } from '@/test/fixtures'

function mountDock(overrides: Record<string, unknown> = {}) {
  return mount(SpecDock, {
    props: {
      readingRouteId: 'r1',
      readingRouteLabel: '主路线',
      activeRouteId: 'r1',
      activeRouteLabel: '主路线',
      snapshots: [],
      selectedSpecId: null,
      generating: false,
      commandPending: false,
      ...overrides,
    },
  })
}

describe('SpecDock collapsed state', () => {
  it('is collapsed by default with a compact summary', () => {
    const wrapper = mountDock({
      snapshots: [
        makeSpecSnapshot({
          id: 'spec-1',
          routeId: 'r1',
          sections: [
            { id: 's1', title: 'A', content: 'a' },
            { id: 's2', title: 'B', content: 'b' },
          ],
          unresolvedItems: [{ text: 'u1', category: 'unresolved' }],
          sourceRefs: [],
        }),
      ],
      selectedSpecId: 'spec-1',
    })
    expect(wrapper.find('[data-test="spec-dock"]').attributes('data-state')).toBe('collapsed')
    const summary = wrapper.find('[data-test="spec-dock-summary"]')
    expect(summary.exists()).toBe(true)
    expect(summary.text()).toContain('主路线')
    expect(summary.text()).toContain('2 个章节')
    expect(summary.text()).toContain('1 个未解决项')
    expect(wrapper.find('[data-test="spec-snapshot-detail"]').exists()).toBe(false)
  })

  it('collapsed summary exposes no raw ids or provenance', () => {
    const wrapper = mountDock({
      snapshots: [makeSpecSnapshot({ id: 'spec-uuid-1', routeId: 'r1' })],
      selectedSpecId: 'spec-uuid-1',
    })
    expect(wrapper.find('[data-test="spec-dock-summary"]').text()).not.toContain('spec-uuid-1')
  })
})

describe('SpecDock expanded state', () => {
  it('expands to show sections, unresolved items and provenance disclosure', async () => {
    const wrapper = mountDock({
      snapshots: [
        makeSpecSnapshot({
          id: 'spec-1',
          routeId: 'r1',
          sections: [{ id: 's1', title: 'Overview', content: 'Body text.' }],
          unresolvedItems: [{ text: 'An open aspect.', category: 'unresolved' }],
          sourceRefs: [{ kind: 'node', refId: 'n1' }],
        }),
      ],
      selectedSpecId: 'spec-1',
    })
    await wrapper.find('[data-test="spec-dock-toggle"]').trigger('click')
    expect(wrapper.find('[data-test="spec-dock"]').attributes('data-state')).toBe('expanded')
    expect(wrapper.find('[data-test="spec-snapshot-detail"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Body text.')
    expect(wrapper.text()).toContain('An open aspect.')
    // 来源默认收起。
    expect(wrapper.find('[data-test="spec-provenance-detail"]').exists()).toBe(false)
    await wrapper.find('[data-test="spec-provenance-toggle"]').trigger('click')
    expect(wrapper.find('[data-test="spec-provenance-detail"]').exists()).toBe(true)
    // 快照历史是紧凑选择器，不是卡片墙导航。
    expect(wrapper.find('[data-test="spec-snapshot-select"]').exists()).toBe(true)
  })

  it('collapse restores the summary without losing the selection', async () => {
    const wrapper = mountDock({
      snapshots: [makeSpecSnapshot({ id: 'spec-1', routeId: 'r1' })],
      selectedSpecId: 'spec-1',
    })
    await wrapper.find('[data-test="spec-dock-toggle"]').trigger('click')
    expect(wrapper.find('[data-test="spec-dock"]').attributes('data-state')).toBe('expanded')
    await wrapper.find('[data-test="spec-dock-toggle"]').trigger('click')
    expect(wrapper.find('[data-test="spec-dock"]').attributes('data-state')).toBe('collapsed')
    expect(wrapper.emitted('select-snapshot')).toBeUndefined()
  })
})

describe('SpecDock route semantics', () => {
  it('states both routes when reading differs from active, and emits generate only', async () => {
    const wrapper = mountDock({
      readingRouteId: 'rB',
      readingRouteLabel: '开放分支',
      activeRouteId: 'rA',
      activeRouteLabel: '当前路线',
    })
    expect(wrapper.find('[data-test="spec-route-warning"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="spec-route-warning"]').text()).toContain('开放分支')
    expect(wrapper.find('[data-test="spec-route-warning"]').text()).toContain('当前路线')
    await wrapper.find('[data-test="spec-dock-toggle"]').trigger('click')
    await wrapper.find('[data-test="generate-spec"]').trigger('click')
    expect(wrapper.emitted('generate-spec')).toHaveLength(1)
    expect(wrapper.emitted('focus-route')).toBeUndefined()
  })
})
