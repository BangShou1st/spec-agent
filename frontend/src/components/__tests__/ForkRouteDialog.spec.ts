import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ForkRouteDialog from '@/components/ForkRouteDialog.vue'
import { makeNode } from '@/test/fixtures'
import type { GraphWorkspaceRouteView } from '@/api/types'

function routeView(id: string, lifecycleStatus: GraphWorkspaceRouteView['lifecycleStatus'], lineage: string[]): GraphWorkspaceRouteView {
  return {
    id,
    label: 'Route ' + id,
    lifecycleStatus,
    isActive: id === 'r1',
    rootNodeId: lineage[0] ?? null,
    tipNodeId: lineage[lineage.length - 1] ?? null,
    createdFromNodeId: null,
    supersedesRouteId: null,
    replacementOfNodeId: null,
    lineageNodeIds: lineage,
  }
}

function mountDialog(overrides: Partial<{
  open: boolean
  node: ReturnType<typeof makeNode> | null
  routes: GraphWorkspaceRouteView[]
  activeRouteId: string | null
  pending: boolean
}> = {}) {
  return mount(ForkRouteDialog, {
    props: {
      open: true,
      node: makeNode({ id: 'n1' }),
      routes: [
        routeView('r1', 'open', ['n1', 'n2']),
        routeView('r2', 'open', ['n1', 'n3']),
        routeView('r3', 'archived', ['n1', 'n4']),
      ],
      activeRouteId: 'r1',
      pending: false,
      ...overrides,
    },
  })
}

describe('ForkRouteDialog', () => {
  it('lists only the routes that contain the node', () => {
    const wrapper = mountDialog()
    const bases = wrapper.findAll('[data-test="fork-base-route"]')
    expect(bases).toHaveLength(3)
  })

  it('allows fork when the selected base route is active and open', async () => {
    const wrapper = mountDialog()
    // 默认选中 active+open 的 r1
    expect(wrapper.find('[data-test="fork-submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-test="fork-label"]').setValue('新分支')
    await wrapper.find('[data-test="fork-submit"]').trigger('click')
    expect(wrapper.emitted('submit')?.[0]).toEqual(['新分支'])
  })

  it('blocks open-but-not-active base routes with an explicit prerequisite', async () => {
    const wrapper = mountDialog()
    await wrapper.findAll('[data-test="fork-base-route"]')[1].setValue()
    expect(wrapper.find('[data-test="fork-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="fork-blocker"]').text()).toContain('先设为当前路线')
    await wrapper.find('[data-test="fork-submit"]').trigger('click')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('blocks non-open base routes with a restore prerequisite', async () => {
    const wrapper = mountDialog()
    await wrapper.findAll('[data-test="fork-base-route"]')[2].setValue()
    expect(wrapper.find('[data-test="fork-blocker"]').text()).toContain('先恢复这条路线')
    expect(wrapper.find('[data-test="restore-base-route"]').text()).toContain('恢复此路线')
    await wrapper.find('[data-test="restore-base-route"]').trigger('click')
    expect(wrapper.emitted('restore-base-route')?.[0]).toEqual(['r3'])
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('keeps the dialog open and offers explicit Activate for an open non-active base', async () => {
    const wrapper = mountDialog()
    await wrapper.findAll('[data-test="fork-base-route"]')[1].setValue()
    expect(wrapper.find('[data-test="activate-base-route"]').text()).toContain('设为当前路线')
    await wrapper.find('[data-test="activate-base-route"]').trigger('click')
    expect(wrapper.emitted('activate-base-route')?.[0]).toEqual(['r2'])
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.find('[data-test="fork-dialog"]').exists()).toBe(true)
  })

  it('never sends a base route id in the payload', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-test="fork-submit"]').trigger('click')
    expect(wrapper.emitted('submit')?.[0]).toEqual([null])
  })

  it('close emits without submitting', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-test="fork-cancel"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(wrapper.emitted('submit')).toBeUndefined()
  })
})
