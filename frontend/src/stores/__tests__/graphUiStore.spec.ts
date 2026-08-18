import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useGraphUiStore } from '@/stores/graphUiStore'
import type { GraphWorkspaceView } from '@/api/types'

const PROJECT_ID = 'p1'
const ACTIVE_ROUTE_ID = 'rActive'

function graphView(overrides: Partial<GraphWorkspaceView> = {}): GraphWorkspaceView {
  return {
    projectId: PROJECT_ID,
    activeRouteId: ACTIVE_ROUTE_ID,
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

  it('focus is independent of active and readingRouteId prefers focus', () => {
    const store = useGraphUiStore()
    expect(store.readingRouteId(ACTIVE_ROUTE_ID)).toBe(ACTIVE_ROUTE_ID)
    store.setFocusRoute('rFocus')
    expect(store.readingRouteId(ACTIVE_ROUTE_ID)).toBe('rFocus')
    store.clearFocusRoute()
    expect(store.readingRouteId(ACTIVE_ROUTE_ID)).toBe(ACTIVE_ROUTE_ID)
  })

  it('hiding the active route is rejected', () => {
    const store = useGraphUiStore()
    store.reconcile(graphView())
    store.hideRoute(ACTIVE_ROUTE_ID)
    expect(store.routeDisplayStates[ACTIVE_ROUTE_ID]).toBeUndefined()
    expect(store.isRouteHidden(ACTIVE_ROUTE_ID)).toBe(false)
  })

  it('show all clears focus and manual dim/hide but preserves lifecycle filters', () => {
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

  it('reset view restores lifecycle filter defaults too', () => {
    const store = useGraphUiStore()
    store.setLifecycleFilter('archived', false)
    store.setLifecycleFilter('deleted', true)
    store.dimRoute('rArchived')
    store.resetView()
    expect(store.lifecycleFilters.archived).toBe(true)
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
})