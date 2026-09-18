import { beforeEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
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

  it('keeps isolate behind the overflow menu and isolates exactly one route', async () => {
    const wrapper = mountSidebar()
    const route = wrapper.find('[data-route-id="r2"]')
    // 默认不常驻：只在 overflow 里。
    expect(route.find('[data-test="route-more"]').attributes('open')).toBeUndefined()
    route.get('[data-test="route-more"]').element.setAttribute('open', '')
    await route.get('[data-test="isolate-route"]').trigger('click')
    const graphUi = useGraphUiStore()
    // 镜头是显式单路线意图：画布上只剩 r2（运行路线 r1 也被移出画布）。
    expect(graphUi.isolatedRouteId).toBe('r2')
    expect(graphUi.focusRouteId).toBe('r2')
    // 不再改写持久化的 display state（旧实现会永久隐藏 r1）。
    expect(graphUi.routeDisplayStates).toEqual({})
  })

  it('只看这条路线 是开关：同一条路线再点一次即退出', async () => {
    const wrapper = mountSidebar()
    const route = wrapper.find('[data-route-id="r2"]')
    route.get('[data-test="route-more"]').element.setAttribute('open', '')
    const button = route.get('[data-test="isolate-route"]')
    await button.trigger('click')
    expect(button.text()).toBe('退出只看')
    await button.trigger('click')
    const graphUi = useGraphUiStore()
    expect(graphUi.isolatedRouteId).toBeNull()
    // 退出镜头保留阅读聚焦与视图，不需要重来一遍。
    expect(graphUi.focusRouteId).toBe('r2')
    expect(button.text()).toBe('只看这条路线')
  })

  it('镜头开启时点击另一张路线卡 = 移动镜头（绝不静默无响应）', async () => {
    const wrapper = mountSidebar()
    const graphUi = useGraphUiStore()
    graphUi.isolateRoute('r2')
    await nextTick()
    await wrapper.find('[data-route-id="r1"] .route-card__label').trigger('click')
    expect(graphUi.isolatedRouteId).toBe('r1')
    expect(graphUi.focusRouteId).toBe('r1')
    expect(wrapper.emitted('locate-route')?.[0]).toEqual(['r1'])
  })

  it('镜头卡片显示"只看中"，其余卡片不显示', async () => {
    const wrapper = mountSidebar()
    useGraphUiStore().isolateRoute('r2')
    await nextTick()
    expect(wrapper.find('[data-route-id="r2"] [data-test="isolate-route-label"]').exists()).toBe(true)
    expect(wrapper.find('[data-route-id="r1"] [data-test="isolate-route-label"]').exists()).toBe(false)
  })

  it('names unlabeled routes by branch origin instead of raw id slices', () => {
    const routes: GraphWorkspaceRouteView[] = [
      { ...routeView('r1', 'open', ['n1']), label: null, branchType: 'fork', isActive: false },
      { ...routeView('r2', 'open', ['n1']), label: '  ', branchType: 'reanswer', isActive: false },
      { ...routeView('r3', 'open', ['n1']), label: null, branchType: 'regenerate', isActive: false },
      { ...routeView('r4', 'open', ['n1']), label: null, branchType: null, isActive: true },
      { ...routeView('r5', 'open', ['n1']), label: null, branchType: null, isActive: false },
    ]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r4', commandPending: false, pendingRouteCommand: null },
    })
    const text = (id: string) => wrapper.find(`[data-route-id="${id}"] .route-card__label`).text()
    expect(text('r1')).toBe('分支路线')
    expect(text('r2')).toBe('重新回答路线')
    expect(text('r3')).toBe('换题路线')
    expect(text('r4')).toBe('主路线')
    expect(text('r5')).toBe('路线')
    expect(wrapper.text()).not.toContain('r1'.slice(0, 8))
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

  it('marks focus and active with independent text labels instead of color only', async () => {
    const routes = [routeView('r1', 'open', ['n1']), routeView('r2', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    useGraphUiStore().setFocusRoute('r2')
    await nextTick()
    const focusRow = wrapper.find('[data-route-id="r2"]')
    const activeRow = wrapper.find('[data-route-id="r1"]')
    expect(focusRow.attributes('aria-current')).toBe('location')
    expect(focusRow.find('.route-card__state').text()).toContain('正在浏览')
    expect(activeRow.find('.route-card__state').text()).toContain('运行路线')
    expect(focusRow.find('.route-card__state').text()).not.toContain('运行路线')
    expect(activeRow.attributes('aria-current')).toBeUndefined()
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

  it('card-click focus changes only the browser reading context', async () => {
    const routes = [routeView('r1', 'open', ['n1']), routeView('r2', 'open', ['n1'])]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    await wrapper.find('[data-route-id="r2"] .route-card__label').trigger('click')
    expect(useGraphUiStore().focusRouteId).toBe('r2')
    // 阅读聚焦绝不改动运行路线：没有任何 runtime 命令被发出。
    expect(wrapper.emitted('activate')).toBeUndefined()
    expect(wrapper.emitted('archive')).toBeUndefined()
    expect(wrapper.find('[data-route-id="r1"]').attributes('aria-current')).toBeUndefined()
  })

  it('isolate 是唯一允许把运行路线移出画布的视图动作', async () => {
    const routes = [
      routeView('r1', 'open', ['n1']),
      routeView('r2', 'open', ['n2']),
      routeView('r3', 'open', ['n3']),
    ]
    const wrapper = mount(RouteSidebar, {
      props: { routes, activeRouteId: 'r1', commandPending: false, pendingRouteCommand: null },
    })
    const graphUi = useGraphUiStore()
    // 运行路线的浏览器态由 reconcile 对齐后端指针（reconcile 需要完整的
    // route 视图，含 lineageNodeIds）。
    graphUi.reconcile({ activeRouteId: 'r1', routes, nodes: [] })
    const r2 = wrapper.find('[data-route-id="r2"]')
    r2.get('[data-test="route-more"]').element.setAttribute('open', '')
    await r2.get('[data-test="isolate-route"]').trigger('click')
    // 手工 hide 仍然保护运行路线；只有"只看"这条显式命令能把它移出画布。
    graphUi.hideRoute('r1')
    expect(graphUi.isolatedRouteId).toBe('r2')
    expect(graphUi.routeDisplayStates.r1).toBeUndefined()
    expect(graphUi.activeRouteId).toBe('r1')
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
    // 归档默认隐藏 → 先勾选筛选让它可见，才能被聚焦。
    wrapper.get('[data-test="route-filters"]').element.setAttribute('open', '')
    await wrapper.get('[data-test="filter-archived"]').setValue(true)
    await wrapper.find('[data-route-id="r2"] .route-card__label').trigger('click')
    expect(useGraphUiStore().focusRouteId).toBe('r2')
    // 关掉筛选时先清 Focus：focusRouteId 绝不指向 filtered-out 路线。
    await wrapper.get('[data-test="filter-archived"]').setValue(false)
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
    const graphUi = useGraphUiStore()
    // 归档现在默认隐藏（DEFAULT_FILTERS.archived = false）：无法被聚焦。
    expect(graphUi.lifecycleFilters.archived).toBe(false)
    await wrapper.find('[data-route-id="r2"] .route-card__label').trigger('click')
    expect(graphUi.focusRouteId).toBeNull()
    // 手工隐藏的路线同样不能被聚焦。
    graphUi.hideRoute('r3')
    await wrapper.find('[data-route-id="r3"] .route-card__label').trigger('click')
    expect(graphUi.focusRouteId).toBeNull()
  })
})
