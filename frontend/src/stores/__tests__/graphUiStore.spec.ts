import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useGraphUiStore } from '@/stores/graphUiStore'
import { getVisibleRouteIds } from '@/graph/graphProjection'
import type { GraphWorkspaceView } from '@/api/types'

const PROJECT_ID = 'p1'
const ACTIVE_ROUTE_ID = 'rActive'

function graphView(overrides: Partial<GraphWorkspaceView> = {}): GraphWorkspaceView {
  return {
    projectId: PROJECT_ID,
    activeRouteId: ACTIVE_ROUTE_ID,
    relations: [],
    routes: [
      {
        id: ACTIVE_ROUTE_ID,
        label: 'Active',
        lifecycleStatus: 'open',
        isActive: true,
        rootNodeId: 'n1',
        tipNodeId: 'n2',
        createdFromNodeId: null,
        supersedesRouteId: null,
        replacementOfNodeId: null,
        lineageNodeIds: ['n1', 'n2'],
      },
      {
        id: 'rFocus',
        label: 'Focus route',
        lifecycleStatus: 'open',
        isActive: false,
        rootNodeId: 'n1',
        tipNodeId: 'n3',
        createdFromNodeId: null,
        supersedesRouteId: null,
        replacementOfNodeId: null,
        lineageNodeIds: ['n1', 'n3'],
      },
      {
        id: 'rArchived',
        label: 'Archived',
        lifecycleStatus: 'archived',
        isActive: false,
        rootNodeId: 'n1',
        tipNodeId: 'n4',
        createdFromNodeId: null,
        supersedesRouteId: null,
        replacementOfNodeId: null,
        lineageNodeIds: ['n1', 'n4'],
      },
    ],
    nodes: [
      {
        id: 'n1',
        projectId: PROJECT_ID,
        parentNodeId: null,
        supersedesNodeId: null,
        question: 'Q1',
        purpose: null,
        options: [],
        allowFreeAnswer: true,
        createdAt: '2026-08-18T00:00:00Z',
        kind: 'INTERACTION',
        subtype: 'QUESTION',
        content: {},
        authorKind: 'AGENT',
        knowledgeStatus: null,
        userEditableDraft: false,
      },
      {
        id: 'n2',
        projectId: PROJECT_ID,
        parentNodeId: 'n1',
        supersedesNodeId: null,
        question: 'Q2',
        purpose: null,
        options: [],
        allowFreeAnswer: true,
        createdAt: '2026-08-18T00:00:00Z',
        kind: 'INTERACTION',
        subtype: 'QUESTION',
        content: {},
        authorKind: 'AGENT',
        knowledgeStatus: null,
        userEditableDraft: false,
      },
      {
        id: 'n3',
        projectId: PROJECT_ID,
        parentNodeId: 'n1',
        supersedesNodeId: null,
        question: 'Q3',
        purpose: null,
        options: [],
        allowFreeAnswer: true,
        createdAt: '2026-08-18T00:00:00Z',
        kind: 'INTERACTION',
        subtype: 'QUESTION',
        content: {},
        authorKind: 'AGENT',
        knowledgeStatus: null,
        userEditableDraft: false,
      },
      {
        id: 'n4',
        projectId: PROJECT_ID,
        parentNodeId: 'n1',
        supersedesNodeId: null,
        question: 'Q4',
        purpose: null,
        options: [],
        allowFreeAnswer: true,
        createdAt: '2026-08-18T00:00:00Z',
        kind: 'INTERACTION',
        subtype: 'QUESTION',
        content: {},
        authorKind: 'AGENT',
        knowledgeStatus: null,
        userEditableDraft: false,
      },
    ],
    answers: [],
    ...overrides,
  }
}

describe('graph ui store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    useGraphUiStore().initProject(PROJECT_ID)
  })

  it('normal selection replaces the old selection', () => {
    const store = useGraphUiStore()
    store.selectNode('n1')
    store.selectNode('n2')
    expect(store.selectedNodeIds).toEqual(['n2'])
    expect(store.primarySelectedNodeId).toBe('n2')
  })

  it('ctrl/cmd toggle adds and removes selection', () => {
    const store = useGraphUiStore()
    store.selectNode('n1')
    store.toggleSelectNode('n2')
    expect(store.selectedNodeIds).toEqual(['n1', 'n2'])
    store.toggleSelectNode('n1')
    expect(store.selectedNodeIds).toEqual(['n2'])
  })

  it('focus is independent of active and is the only reading route', () => {
    const store = useGraphUiStore()
    expect(store.readingRouteId(ACTIVE_ROUTE_ID)).toBeNull()
    store.setFocusRoute('rFocus')
    expect(store.readingRouteId(ACTIVE_ROUTE_ID)).toBe('rFocus')
    store.clearFocusRoute()
    expect(store.readingRouteId(ACTIVE_ROUTE_ID)).toBeNull()
  })

  it('hiding the active route is rejected', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    store.hideRoute(ACTIVE_ROUTE_ID)
    expect(store.routeDisplayStates[ACTIVE_ROUTE_ID]).toBeUndefined()
    expect(store.isRouteHidden(ACTIVE_ROUTE_ID)).toBe(false)
  })

  it('hiding the focused route clears focus before hiding', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    store.setFocusRoute('rFocus')
    store.hideRoute('rFocus')
    expect(store.focusRouteId).toBeNull()
    expect(store.isRouteHidden('rFocus')).toBe(true)
  })

  it('focus never points at a manually hidden route', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    store.hideRoute('rFocus')
    store.setFocusRoute('rFocus')
    expect(store.focusRouteId).toBeNull()
    store.restoreRouteDisplay('rFocus')
    store.setFocusRoute('rFocus')
    expect(store.focusRouteId).toBe('rFocus')
  })

  it('reconcile clears focus when the focused route is manually hidden', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    store.setFocusRoute('rFocus')
    // Hidden state that bypassed hideRoute (e.g. persisted) still repairs Focus.
    store.routeDisplayStates = { ...store.routeDisplayStates, rFocus: 'hidden' }
    store.reconcile(graphView())
    expect(store.focusRouteId).toBeNull()
  })

  it('show all clears manual dim/hide but preserves focus and lifecycle filters', () => {
    const store = useGraphUiStore()
    store.setFocusRoute('rFocus')
    store.dimRoute('rArchived')
    store.hideRoute('rFocus')
    store.setLifecycleFilter('archived', false)
    store.showAll()
    expect(store.focusRouteId).toBeNull()
    expect(store.routeDisplayStates).toEqual({})
    expect(store.lifecycleFilters.archived).toBe(false)
  })

  it('isolate 只看这条路线 只显示这一条路线（连运行路线一起隐藏）', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    store.isolateRoute('rFocus')
    // 镜头是显式单路线意图：它压过 Active 的强制可见，也压过生命周期筛选。
    expect(store.isolatedRouteId).toBe('rFocus')
    expect(getVisibleRouteIds(graphView(), store)).toEqual(new Set(['rFocus']))
    // 镜头不写持久化的 display state：退出时视图精确还原。
    expect(store.routeDisplayStates).toEqual({})
  })

  it('isolate 连续两次都生效（第二次不再被运行路线挡住）', () => {
    const store = useGraphUiStore()
    const view = graphView()
    store.reconcile(view)

    store.isolateRoute(ACTIVE_ROUTE_ID)
    expect(getVisibleRouteIds(view, store)).toEqual(new Set([ACTIVE_ROUTE_ID]))

    // 旧实现把 active 排除在隐藏之外 → 第二次只看一条非运行路线时，
    // 运行路线仍留在画布上，看起来"没生效"。
    store.isolateRoute('rFocus')
    expect(getVisibleRouteIds(view, store)).toEqual(new Set(['rFocus']))
    expect(store.focusRouteId).toBe('rFocus')
  })

  it('isolate 带走阅读聚焦，绝不留下指向不可见路线的 Focus', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    store.setFocusRoute(ACTIVE_ROUTE_ID)
    store.isolateRoute('rFocus')
    expect(store.focusRouteId).toBe('rFocus')
    // 运行路线此时不可见，但 store.activeRouteId（Runtime 事实）不受影响。
    expect(store.activeRouteId).toBe(ACTIVE_ROUTE_ID)
  })

  it('退出镜头：clearIsolation 只清镜头，showAll 顺带清手工 dim/hide', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    store.setFocusRoute('rFocus')
    store.isolateRoute('rFocus')
    store.clearIsolation()
    expect(store.isolatedRouteId).toBeNull()
    expect(store.focusRouteId).toBe('rFocus')

    store.isolateRoute('rFocus')
    store.dimRoute('rArchived')
    store.showAll()
    expect(store.isolatedRouteId).toBeNull()
    expect(store.routeDisplayStates).toEqual({})
    expect(store.focusRouteId).toBe('rFocus')
  })

  it('reconcile 只在路线真的消失时清掉镜头，不因筛选而清', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    store.isolateRoute('rFocus')
    store.setLifecycleFilter('archived', true)
    store.reconcile(graphView())
    expect(store.isolatedRouteId).toBe('rFocus')
    // 镜头中的路线即使被筛选关闭，Focus 也由镜头保住（用户明确要求看它）。
    expect(store.focusRouteId).toBe('rFocus')

    store.reconcile({
      activeRouteId: ACTIVE_ROUTE_ID,
      routes: graphView().routes.filter((route) => route.id !== 'rFocus'),
      nodes: graphView().nodes,
    })
    expect(store.isolatedRouteId).toBeNull()
    expect(store.focusRouteId).toBeNull()
  })

  it('切换项目清掉镜头', () => {
    const store = useGraphUiStore()
    store.isolateRoute('rFocus')
    store.initProject('p2')
    expect(store.isolatedRouteId).toBeNull()
  })

  it('reset view restores lifecycle filter defaults too', () => {
    const store = useGraphUiStore()
    store.setLifecycleFilter('archived', true)
    store.setLifecycleFilter('deleted', true)
    store.dimRoute('rArchived')
    store.resetView()
    // 归档现在是"收起路线"的唯一动作 → 默认隐藏。
    expect(store.lifecycleFilters.archived).toBe(false)
    expect(store.lifecycleFilters.deleted).toBe(false)
    expect(store.focusRouteId).toBeNull()
    expect(store.routeDisplayStates).toEqual({})
  })

  it('expanded node ids toggle', () => {
    const store = useGraphUiStore()
    store.toggleExpanded('n1')
    expect(store.expandedNodeIds).toEqual(['n1'])
    store.toggleExpanded('n1')
    expect(store.expandedNodeIds).toEqual([])
  })

  it('sidebar open/width state persists into localStorage', () => {
    const store = useGraphUiStore()
    store.setLeftSidebar({ open: false, width: 360 })
    store.setRightSidebar({ open: true, width: 520 })
    expect(store.leftSidebarOpen).toBe(false)
    expect(store.leftSidebarWidth).toBe(360)
    expect(store.rightSidebarOpen).toBe(true)
    expect(store.rightSidebarWidth).toBe(520)
    const saved = JSON.parse(localStorage.getItem('spec-agent.workspace-ui.v1') ?? '{}')
    expect(saved.leftSidebar).toEqual({ open: false, width: 360 })
    expect(saved.rightSidebar).toEqual({ open: true, width: 520 })
  })

  it('reconcile drops selected nodes that no longer exist', () => {
    const store = useGraphUiStore()
    store.selectNode('n1')
    store.toggleSelectNode('ghost')
    store.reconcile(graphView())
    expect(store.selectedNodeIds).toEqual(['n1'])
  })

  it('reconcile clears focus when that route is no longer visible', () => {
    const store = useGraphUiStore()
    store.setLifecycleFilter('archived', false)
    store.setFocusRoute('rArchived')
    const view = graphView()
    view.activeRouteId = ACTIVE_ROUTE_ID
    store.reconcile(view)
    // archived routes are hidden by the default lifecycle filter.
    expect(store.focusRouteId).toBeNull()
  })

  it('reconcile repairs a persisted hidden state on the active route', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    // Simulate a persisted hidden state that somehow landed on the active route.
    store.routeDisplayStates = { ...store.routeDisplayStates, [ACTIVE_ROUTE_ID]: 'hidden' }
    store.persistProjectState()
    const saved = JSON.parse(localStorage.getItem('spec-agent.graph-layout.v1.p1') ?? '{}')
    expect(saved.routeDisplayStates[ACTIVE_ROUTE_ID]).toBe('hidden')
    store.reconcile(graphView())
    expect(store.routeDisplayStates[ACTIVE_ROUTE_ID]).toBe('normal')
    const after = JSON.parse(localStorage.getItem('spec-agent.graph-layout.v1.p1') ?? '{}')
    expect(after.routeDisplayStates[ACTIVE_ROUTE_ID]).toBe('normal')
  })

  it('reconcile keeps a normal display state on the active route', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    expect(store.isRouteHidden(ACTIVE_ROUTE_ID)).toBe(false)
  })

  it('node positions persist per project', () => {
    const store = useGraphUiStore()
    store.setNodePosition('n1', { x: 123, y: 456 })
    const saved = JSON.parse(localStorage.getItem('spec-agent.graph-layout.v1.p1') ?? '{}')
    expect(saved.nodePositions.n1).toEqual({ x: 123, y: 456 })
    useGraphUiStore().initProject('p2')
    expect(useGraphUiStore().nodePositions).toEqual({})
  })

  it('persists fixed sidebar open state and clamped widths without floating-window state', () => {
    const store = useGraphUiStore()
    store.setLeftSidebar({ open: false, width: 9999 })
    store.setRightSidebar({ open: true, width: -10 })

    const saved = JSON.parse(localStorage.getItem('spec-agent.workspace-ui.v1') ?? '{}')
    expect(saved.leftSidebar.open).toBe(false)
    expect(saved.leftSidebar.width).toBe(420)
    expect(saved.rightSidebar.open).toBe(true)
    expect(saved.rightSidebar.width).toBe(300)
    expect('floatingWindows' in store).toBe(false)
  })

})
