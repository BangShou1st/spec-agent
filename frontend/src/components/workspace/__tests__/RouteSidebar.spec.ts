import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import RouteSidebar from '@/components/workspace/RouteSidebar.vue'
import { useGraphUiStore } from '@/stores/graphUiStore'
import type { GraphWorkspaceRouteView } from '@/api/types'

function routeView(id: string, lifecycleStatus: GraphWorkspaceRouteView['lifecycleStatus'], lineage: string[]): GraphWorkspaceRouteView {
  return {
    id,
    label: 'Route ' + id,
    lifecycleStatus,
    isActive: false,
    rootNodeId: lineage[0] ?? null,
    tipNodeId: lineage[lineage.length - 1] ?? null,
    createdFromNodeId: null,
    supersedesRouteId: null,
    replacementOfNodeId: null,
    lineageNodeIds: lineage,
  }
}

describe('route sidebar', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    useGraphUiStore().initProject('p1')
  })

  it('shows chinese lifecycle labels and independent active indicator per route', () => {
    const routes = [
      routeView('r1', 'open', ['n1', 'n2']),
      routeView('r2', 'superseded', ['n1', 'n3']),
      routeView('r3', 'archived', ['n1', 'n4']),
      routeView('r4', 'deleted', ['n1', 'n5']),
    ]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    const text = wrapper.text()
    expect(text).toContain('开放')
    expect(text).toContain('已替代')
    expect(text).toContain('已归档')
    expect(text).toContain('已删除')
    expect(text).toContain('当前路线')
    // lineage length shown for route 1
    expect(text).toContain('2')
    const active = wrapper.find('[data-route-id="r1"]')
    expect(active.text()).toContain('当前路线')
    expect(wrapper.find('[data-route-id="r2"]').text()).not.toContain('当前路线')
  })

  it('separates view-only actions from runtime route actions', () => {
    const routes = [routeView('r1', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    expect(wrapper.find('[data-test="view-actions-group"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="runtime-actions-group"]').exists()).toBe(true)
  })

  it('locate emits only a viewport request and never changes focus', async () => {
    const routes = [routeView('r1', 'open', ['n1']), routeView('r2', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    await wrapper.find('[data-route-id="r2"] [data-test="locate-route"]').trigger('click')
    expect(wrapper.emitted('locate-route')?.[0]).toEqual(['r2'])
    expect(useGraphUiStore().focusRouteId).toBeNull()
  })

  it('focus changes only the browser reading context', async () => {
    const routes = [routeView('r1', 'open', ['n1']), routeView('r2', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    await wrapper.find('[data-route-id="r2"] [data-test="focus-route"]').trigger('click')
    expect(useGraphUiStore().focusRouteId).toBe('r2')
  })

  it('hide never hides the active route', async () => {
    const routes = [routeView('r1', 'open', ['n1']), routeView('r2', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    await wrapper.find('[data-route-id="r1"] [data-test="hide-route"]').trigger('click')
    expect(useGraphUiStore().routeDisplayStates.r1).toBeUndefined()
    await wrapper.find('[data-route-id="r2"] [data-test="hide-route"]').trigger('click')
    expect(useGraphUiStore().routeDisplayStates.r2).toBe('hidden')
  })

  it('runtime actions emit the route id upward', async () => {
    const routes = [routeView('r2', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    await wrapper.find('[data-test="activate-route"]').trigger('click')
    expect(wrapper.emitted('activate')?.[0]).toEqual(['r2'])
  })

  it('lifecycle filters toggle through the ui store', async () => {
    const routes = [routeView('r1', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    await wrapper.find('[data-test="filter-archived"]').setValue(false)
    expect(useGraphUiStore().lifecycleFilters.archived).toBe(false)
  })
})
