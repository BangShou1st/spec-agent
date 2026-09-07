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

  function mountSidebar(
    routes: GraphWorkspaceRouteView[] = [
      routeView('r1', 'open', ['n1', 'n2']),
      routeView('r2', 'open', ['n1', 'n3']),
    ],
    activeRouteId = 'r1',
  ) {
    return mount(RouteSidebar, {
      props: { routes, activeRouteId, commandPending: false, pendingRouteCommand: null },
    })
  }

  it('renders route rows as navigation first and keeps management controls behind overflow', () => {
    const wrapper = mountSidebar()
    const route = wrapper.find('[data-route-id="r1"]')

    expect(route.find('[data-test="route-primary"]').exists()).toBe(true)
    expect(route.find('[data-test="route-more"]').exists()).toBe(true)
    expect(route.find('[data-test="route-more"]').attributes('open')).toBeUndefined()
  })

  it('clicking the route row focuses and locates without activating runtime', async () => {
    const wrapper = mountSidebar()
    await wrapper.find('[data-route-id="r2"] .route-card__label').trigger('click')

    expect(useGraphUiStore().focusRouteId).toBe('r2')
    expect(wrapper.emitted('locate-route')?.[0]).toEqual(['r2'])
    expect(wrapper.emitted('activate')).toBeUndefined()
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
    // 生命周期筛选收进闭合的 overflow；open 路线默认不展示常驻徽标。
    wrapper.get('[data-test="route-filters"]').element.setAttribute('open', '')
    const text = wrapper.text()
    expect(text).toContain('已替代')
    expect(text).toContain('已归档')
    expect(text).toContain('已删除')
    expect(text).toContain('运行路线')
    // lineage length shown for route 1
    expect(text).toContain('2')
    const active = wrapper.find('[data-route-id="r1"]')
    expect(active.text()).toContain('运行路线')
    expect(wrapper.find('[data-route-id="r2"]').text()).not.toContain('运行路线')
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
    const route = wrapper.find('[data-route-id="r2"]')
    route.get('[data-test="route-more"]').element.setAttribute('open', '')
    await route.get('[data-test="locate-route"]').trigger('click')
    expect(wrapper.emitted('locate-route')?.[0]).toEqual(['r2'])
    expect(useGraphUiStore().focusRouteId).toBeNull()
  })

  it('clicking a route card focuses it and requests viewport location', async () => {
    const routes = [routeView('r1', 'open', ['n1']), routeView('r2', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    await wrapper.find('[data-route-id="r2"] .route-card__label').trigger('click')
    expect(useGraphUiStore().focusRouteId).toBe('r2')
    expect(wrapper.emitted('locate-route')?.[0]).toEqual(['r2'])
  })

  it('focus changes only the browser reading context', async () => {
    const routes = [routeView('r1', 'open', ['n1']), routeView('r2', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    const route = wrapper.find('[data-route-id="r2"]')
    route.get('[data-test="route-more"]').element.setAttribute('open', '')
    await route.get('[data-test="focus-route"]').trigger('click')
    expect(useGraphUiStore().focusRouteId).toBe('r2')
  })

  it('hide never hides the active route', async () => {
    const routes = [routeView('r1', 'open', ['n1']), routeView('r2', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    const r1 = wrapper.find('[data-route-id="r1"]')
    r1.get('[data-test="route-more"]').element.setAttribute('open', '')
    await r1.get('[data-test="hide-route"]').trigger('click')
    expect(useGraphUiStore().routeDisplayStates.r1).toBeUndefined()
    const r2 = wrapper.find('[data-route-id="r2"]')
    r2.get('[data-test="route-more"]').element.setAttribute('open', '')
    await r2.get('[data-test="hide-route"]').trigger('click')
    expect(useGraphUiStore().routeDisplayStates.r2).toBe('hidden')
  })

  it('runtime actions emit the route id upward', async () => {
    const routes = [routeView('r2', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    const route = wrapper.find('[data-route-id="r2"]')
    route.get('[data-test="route-more"]').element.setAttribute('open', '')
    await route.get('[data-test="activate-route"]').trigger('click')
    expect(wrapper.emitted('activate')?.[0]).toEqual(['r2'])
  })

  it('lifecycle filters toggle through the ui store', async () => {
    const routes = [routeView('r1', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    wrapper.get('[data-test="route-filters"]').element.setAttribute('open', '')
    await wrapper.get('[data-test="filter-archived"]').setValue(false)
    expect(useGraphUiStore().lifecycleFilters.archived).toBe(false)
  })

  it('turning off the lifecycle filter of the focused route clears focus first', async () => {
    const routes = [
      routeView('r1', 'open', ['n1']),
      routeView('r2', 'archived', ['n1']),
    ]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    const route = wrapper.find('[data-route-id="r2"]')
    route.get('[data-test="route-more"]').element.setAttribute('open', '')
    await route.get('[data-test="focus-route"]').trigger('click')
    expect(useGraphUiStore().focusRouteId).toBe('r2')
    wrapper.get('[data-test="route-filters"]').element.setAttribute('open', '')
    await wrapper.get('[data-test="filter-archived"]').setValue(false)
    // Focus 被清除，筛选才生效：focusRouteId 绝不指向 filtered-out 路线。
    expect(useGraphUiStore().focusRouteId).toBeNull()
    expect(useGraphUiStore().lifecycleFilters.archived).toBe(false)
  })

  it('focus never selects a filtered-out or hidden route', async () => {
    const routes = [
      routeView('r1', 'open', ['n1']),
      routeView('r2', 'archived', ['n1']),
      routeView('r3', 'open', ['n1']),
    ]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    wrapper.get('[data-test="route-filters"]').element.setAttribute('open', '')
    await wrapper.get('[data-test="filter-archived"]').setValue(false)
    const r2 = wrapper.find('[data-route-id="r2"]')
    r2.get('[data-test="route-more"]').element.setAttribute('open', '')
    await r2.get('[data-test="focus-route"]').trigger('click')
    expect(useGraphUiStore().focusRouteId).toBeNull()
    const r3 = wrapper.find('[data-route-id="r3"]')
    r3.get('[data-test="route-more"]').element.setAttribute('open', '')
    await r3.get('[data-test="hide-route"]').trigger('click')
    await r3.get('[data-test="focus-route"]').trigger('click')
    expect(useGraphUiStore().focusRouteId).toBeNull()
  })
})
