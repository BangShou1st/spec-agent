// 文件名:RouteActionDialog.spec.ts
// 用途:RouteActionDialog(fork 模式)单元测试,验证不提供路线选择器、共享节点需先选查看路线、归档来源可恢复、未回答拦截与取消行为。
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RouteActionDialog from '@/features/workspace/components/RouteActionDialog.vue'
import { makeNode } from '@/test/fixtures'
import type { GraphWorkspaceRouteView } from '@/shared/contracts/types'

function routeView(status: GraphWorkspaceRouteView['lifecycleStatus'] = 'open'): GraphWorkspaceRouteView {
  return {
    id: 'r1',
    label: '当前路线',
    lifecycleStatus: status,
    isActive: true,
    rootNodeId: 'n1',
    tipNodeId: 'n1',
    createdFromNodeId: null,
    supersedesRouteId: null,
    replacementOfNodeId: null,
    lineageNodeIds: ['n1'],
  }
}

function mountDialog(sourceRoute: GraphWorkspaceRouteView | null = routeView(), finalized = true) {
  return mount(RouteActionDialog, {
    props: { mode: 'fork' as const, open: true, node: makeNode({ id: 'n1' }), sourceRoute, pending: false, finalized },
  })
}

describe('ForkRouteDialog', () => {
  it('does not expose a route picker and submits only the label', async () => {
    const wrapper = mountDialog()
    expect(wrapper.find('[data-test="fork-base-route"]').exists()).toBe(false)
    await wrapper.find('[data-test="fork-label"]').setValue('新分支')
    await wrapper.find('[data-test="fork-submit"]').trigger('click')
    expect(wrapper.emitted('submit')?.[0]).toEqual(['新分支'])
  })

  it('blocks when no current reading route is selected', () => {
    const wrapper = mountDialog(null)
    expect(wrapper.text()).toContain('当前查看')
    expect(wrapper.find('[data-test="fork-submit"]').attributes('disabled')).toBeDefined()
  })

  it('offers restore for an archived source route', async () => {
    const wrapper = mountDialog(routeView('archived'))
    await wrapper.find('[data-test="restore-base-route"]').trigger('click')
    expect(wrapper.emitted('restore-source')?.[0]).toEqual(['r1'])
    expect(wrapper.find('[data-test="fork-submit"]').attributes('disabled')).toBeDefined()
  })

  it('requires a finalized answer', () => {
    const wrapper = mountDialog(routeView(), false)
    expect(wrapper.text()).toContain('还没有回答')
    expect(wrapper.find('[data-test="fork-submit"]').attributes('disabled')).toBeDefined()
  })

  it('closes without submitting', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-test="fork-cancel"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(wrapper.emitted('submit')).toBeUndefined()
  })
})
