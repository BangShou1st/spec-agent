import { defineStore } from 'pinia'
import type { RouteLifecycleStatus } from '@/api/types'
import {
  LEFT_SIDEBAR_RANGE,
  RIGHT_SIDEBAR_RANGE,
  DEFAULT_WORKSPACE_UI_V2,
  loadProjectGraphPreferencesV2,
  loadWorkspaceUiPreferences,
  loadWorkspaceUiPreferencesV2,
  saveProjectGraphPreferences,
  saveProjectGraphPreferencesV2,
  saveWorkspaceUiPreferences,
  saveWorkspaceUiPreferencesV2,
} from '@/graph/graphLayoutStorage'
import type {
  GraphPosition,
  GraphRouteDisplayState,
  ProjectGraphPreferencesV1,
  ProjectGraphPreferencesV2,
  WorkspaceUiPreferencesV1,
  WorkspaceUiPreferencesV2,
  FloatingWindowPreference,
} from '@/graph/graphTypes'
import { buildVisualInstances } from '@/graph/graphVisualIdentity'

const DEFAULT_FILTERS: Record<RouteLifecycleStatus, boolean> = {
  open: true,
  superseded: true,
  archived: true,
  deleted: false,
}

/**
 * Browser-only graph workspace UI state.
 *
 * This store owns selection, multi-selection, focus route, lifecycle
 * filters, dim/hide display state, expanded nodes, local node positions
 * and sidebar layout. It never owns Runtime facts: Active route, lifecycle,
 * answers, nodes, routes and Spec content stay in `workspaceStore`/backend.
 *
 * Focus Route is the only explicit browser reading context. It never changes
 * the Active route and shared nodes never infer a route from Active.
 *
 * Persisted locally: per-project node positions + route display states,
 * and the global sidebar open/width preferences. Never persisted:
 * selection, expanded nodes, focus, pan/zoom.
 */
export const useGraphUiStore = defineStore('graphUi', {
  state: () => {
    const workspaceUi = loadWorkspaceUiPreferences()
    const floatingUi = loadWorkspaceUiPreferencesV2()
    return {
      projectId: null as string | null,
      activeRouteId: null as string | null,
      selectedNodeIds: [] as string[],
      primarySelectedNodeId: null as string | null,
      // One-shot "open this node's editor" request (e.g. right after creating
      // an idea). The matching card consumes and clears it.
      pendingEditNodeKey: null as string | null,
      selectedEdgeId: null as string | null,
      selectedSharedEdgeRouteIds: [] as string[],
      focusRouteId: null as string | null,
      // Optional semantic-relation layer visibility. Browser-only ephemeral:
      // never persisted, always defaults to false. The Inspector remains
      // the canonical place to inspect relations.
      showRelationLayer: false,
      lifecycleFilters: { ...DEFAULT_FILTERS } as Record<RouteLifecycleStatus, boolean>,
      routeDisplayStates: {} as Record<string, GraphRouteDisplayState>,
      expandedNodeIds: [] as string[],
      nodePositions: {} as Record<string, GraphPosition>,
      floatingWindows: { ...floatingUi.windows } as WorkspaceUiPreferencesV2['windows'],
      windowZOrder: ['routes', 'inspector'] as Array<'routes' | 'inspector'>,
      leftSidebarOpen: workspaceUi.leftSidebar.open,
      leftSidebarWidth: workspaceUi.leftSidebar.width,
      rightSidebarOpen: workspaceUi.rightSidebar.open,
      rightSidebarWidth: workspaceUi.rightSidebar.width,
    }
  },
  getters: {
    isRouteHidden(state) {
      return (routeId: string): boolean => state.routeDisplayStates[routeId] === 'hidden'
    },
    isRouteDimmed(state) {
      return (routeId: string): boolean => state.routeDisplayStates[routeId] === 'dimmed'
    },
  },
  actions: {
    /**
     * Switches the browser-only UI to another project: clears per-project
     * view state and loads that project's saved layout.
     */
    initProject(projectId: string): void {
      this.projectId = projectId
      this.selectedNodeIds = []
      this.primarySelectedNodeId = null
      this.focusRouteId = null
      this.expandedNodeIds = []
      const v2 = loadProjectGraphPreferencesV2(projectId)
      this.nodePositions = { ...v2.nodePositions }
      this.routeDisplayStates = { ...v2.routeDisplayStates }
    },

    /** Returns the explicit browser reading route, never the runtime Active route. */
    readingRouteId(_activeRouteId?: string | null): string | null {
      return this.focusRouteId
    },

    /** Normal click: single selection replaces the old selection. */
    selectNode(nodeId: string): void {
      this.selectedNodeIds = [nodeId]
      this.primarySelectedNodeId = nodeId
      this.clearEdgeSelection()
    },

    /** Asks the matching node card to open its editor, then clears itself. */
    requestNodeEdit(nodeKey: string): void {
      this.pendingEditNodeKey = nodeKey
    },

    consumeNodeEditRequest(nodeKey: string): boolean {
      if (this.pendingEditNodeKey !== nodeKey) return false
      this.pendingEditNodeKey = null
      return true
    },

    /** Ctrl/Cmd + click: add/remove from multi-selection. */
    toggleSelectNode(nodeId: string): void {
      this.clearEdgeSelection()
      if (this.selectedNodeIds.includes(nodeId)) {
        this.selectedNodeIds = this.selectedNodeIds.filter((id) => id !== nodeId)
        if (this.primarySelectedNodeId === nodeId) {
          this.primarySelectedNodeId = this.selectedNodeIds[0] ?? null
        }
      } else {
        this.selectedNodeIds = [...this.selectedNodeIds, nodeId]
        this.primarySelectedNodeId = nodeId
      }
    },

    /** Mirrors a Vue Flow selection batch into the UI store. */
    setSelection(nodeIds: string[]): void {
      this.selectedNodeIds = [...nodeIds]
      if (nodeIds.length > 0) this.clearEdgeSelection()
      if (!this.primarySelectedNodeId || !nodeIds.includes(this.primarySelectedNodeId)) {
        this.primarySelectedNodeId = nodeIds[0] ?? null
      }
    },

    clearSelection(): void {
      this.selectedNodeIds = []
      this.primarySelectedNodeId = null
    },

    selectEdge(edgeId: string, routeIds: string[]): void {
      this.selectedEdgeId = edgeId
      this.selectedSharedEdgeRouteIds = [...routeIds]
      this.clearSelection()
    },

    clearEdgeSelection(): void {
      this.selectedEdgeId = null
      this.selectedSharedEdgeRouteIds = []
    },

    setFocusRoute(routeId: string | null): void {
      // Focus is a reading context for visible routes only: a manually
      // hidden route can never become the Focus route.
      if (routeId !== null && this.routeDisplayStates[routeId] === 'hidden') {
        return
      }
      this.focusRouteId = routeId
    },

    /** Toggles the optional semantic-relation layer on the canvas. */
    setShowRelationLayer(visible: boolean): void {
      this.showRelationLayer = visible
    },

    clearFocusRoute(): void {
      this.focusRouteId = null
    },

    setLifecycleFilter(status: RouteLifecycleStatus, visible: boolean): void {
      this.lifecycleFilters = { ...this.lifecycleFilters, [status]: visible }
    },

    /**
     * Hide keeps route-exclusive elements out of the current browser view.
     * The Active route can never be hidden. Hiding the focused route clears
     * Focus first: Focus must never point at a hidden route.
     */
    hideRoute(routeId: string): void {
      if (routeId === this.activeRouteId) {
        return
      }
      if (routeId === this.focusRouteId) {
        this.focusRouteId = null
      }
      this.setRouteDisplayState(routeId, 'hidden')
    },

    /** Dim keeps the route visible with lower visual weight. */
    dimRoute(routeId: string): void {
      this.setRouteDisplayState(routeId, 'dimmed')
    },

    restoreRouteDisplay(routeId: string): void {
      this.setRouteDisplayState(routeId, 'normal')
    },

    setRouteDisplayState(routeId: string, state: GraphRouteDisplayState): void {
      this.routeDisplayStates = { ...this.routeDisplayStates, [routeId]: state }
      this.persistProjectState()
    },

    /** Clears isolate/manual dim/hide but preserves Focus and lifecycle filters. */
    showAll(): void {
      this.routeDisplayStates = {}
      this.persistProjectState()
    },

    /** Separate visibility-only isolate mode; it never changes Focus or Active. */
    isolateRoute(routeId: string, routeIds: string[]): void {
      const next: Record<string, GraphRouteDisplayState> = {}
      for (const candidate of routeIds) {
        if (candidate !== routeId && candidate !== this.activeRouteId) {
          next[candidate] = 'hidden'
        }
      }
      this.routeDisplayStates = next
      this.persistProjectState()
    },

    /**
     * Restores the full default view: clears Focus, manual display state and
     * resets lifecycle filters to their defaults.
     */
    resetView(): void {
      this.focusRouteId = null
      this.routeDisplayStates = {}
      this.lifecycleFilters = { ...DEFAULT_FILTERS }
      this.persistProjectState()
    },

    toggleExpanded(nodeId: string): void {
      this.expandedNodeIds = this.expandedNodeIds.includes(nodeId)
        ? this.expandedNodeIds.filter((id) => id !== nodeId)
        : [...this.expandedNodeIds, nodeId]
    },

    setFloatingWindow(name: 'routes' | 'inspector', value: Partial<FloatingWindowPreference>): void {
      this.floatingWindows = {
        ...this.floatingWindows,
        [name]: { ...this.floatingWindows[name], ...value },
      }
      this.persistWorkspaceV2()
    },

    bringWindowToFront(name: 'routes' | 'inspector'): void {
      this.windowZOrder = [...this.windowZOrder.filter((item) => item !== name), name]
    },

    resetWindows(): void {
      this.floatingWindows = {
        routes: { ...DEFAULT_WORKSPACE_UI_V2.windows.routes },
        inspector: { ...DEFAULT_WORKSPACE_UI_V2.windows.inspector },
      }
      this.windowZOrder = ['routes', 'inspector']
      this.persistWorkspaceV2()
    },

    setNodePosition(nodeId: string, position: GraphPosition): void {
      this.nodePositions = { ...this.nodePositions, [nodeId]: position }
      this.persistProjectState()
    },

    /** Persists a batch of node positions (used on drag stop). */
    setNodePositions(positions: Record<string, GraphPosition>): void {
      this.nodePositions = { ...this.nodePositions, ...positions }
      this.persistProjectState()
    },

    setLeftSidebar(prefs: { open: boolean; width: number }): void {
      this.leftSidebarOpen = prefs.open
      this.leftSidebarWidth = clampSidebarWidth(prefs.width, LEFT_SIDEBAR_RANGE)
      this.persistWorkspaceState()
    },

    setRightSidebar(prefs: { open: boolean; width: number }): void {
      this.rightSidebarOpen = prefs.open
      this.rightSidebarWidth = clampSidebarWidth(prefs.width, RIGHT_SIDEBAR_RANGE)
      this.persistWorkspaceState()
    },

    /**
     * Reconciles browser-only view state against the canonical graph after
     * every refresh: drops stale selections, clears Focus on routes that are
     * no longer visible, and repairs any persisted hidden state on the
     * Active route.
     */
    reconcile(view: {
      activeRouteId: string | null
      routes: { id: string; lifecycleStatus: RouteLifecycleStatus }[]
      nodes: { id: string }[]
    } | null): void {
      if (!view) {
        return
      }
      this.activeRouteId = view.activeRouteId
      const nodeIds = new Set(view.nodes.map((node) => node.id))
      if ('routes' in view && 'nodes' in view) {
        for (const instance of buildVisualInstances(view as Parameters<typeof buildVisualInstances>[0])) {
          nodeIds.add(instance.visualNodeKey)
        }
      }
      this.selectedNodeIds = this.selectedNodeIds.filter((id) => nodeIds.has(id))
      if (
        this.primarySelectedNodeId &&
        !nodeIds.has(this.primarySelectedNodeId)
      ) {
        this.primarySelectedNodeId = this.selectedNodeIds[0] ?? null
      }

      if (this.focusRouteId) {
        const focusRoute = view.routes.find((route) => route.id === this.focusRouteId)
        const visible =
          focusRoute !== undefined &&
          this.lifecycleFilters[focusRoute.lifecycleStatus] === true &&
          this.routeDisplayStates[focusRoute.id] !== 'hidden'
        if (!visible) {
          this.focusRouteId = null
        }
      }

      if (
        view.activeRouteId &&
        this.routeDisplayStates[view.activeRouteId] === 'hidden'
      ) {
        // A persisted hidden state can never hide the Active route; it is
        // repaired to normal and the fix is persisted too.
        this.routeDisplayStates = {
          ...this.routeDisplayStates,
          [view.activeRouteId]: 'normal',
        }
        this.persistProjectState()
      }
    },

    persistProjectState(): void {
      if (!this.projectId) {
        return
      }
      const prefs: ProjectGraphPreferencesV1 = {
        version: 1,
        nodePositions: { ...this.nodePositions },
        routeDisplayStates: { ...this.routeDisplayStates },
      }
      saveProjectGraphPreferences(this.projectId, prefs)
      const prefsV2: ProjectGraphPreferencesV2 = {
        version: 2,
        nodePositions: { ...this.nodePositions },
        routeDisplayStates: { ...this.routeDisplayStates },
      }
      saveProjectGraphPreferencesV2(this.projectId, prefsV2)
    },

    persistWorkspaceState(): void {
      const prefs: WorkspaceUiPreferencesV1 = {
        version: 1,
        leftSidebar: { open: this.leftSidebarOpen, width: this.leftSidebarWidth },
        rightSidebar: { open: this.rightSidebarOpen, width: this.rightSidebarWidth },
      }
      saveWorkspaceUiPreferences(prefs)
      this.persistWorkspaceV2()
    },

    persistWorkspaceV2(): void {
      const prefs: WorkspaceUiPreferencesV2 = {
        version: 2,
        windows: {
          routes: { ...this.floatingWindows.routes },
          inspector: { ...this.floatingWindows.inspector },
        },
      }
      saveWorkspaceUiPreferencesV2(prefs)
    },
  },
})

function clampSidebarWidth(
  width: number,
  range: { min: number; max: number; default: number },
): number {
  if (!Number.isFinite(width)) return range.default
  return Math.min(range.max, Math.max(range.min, width))
}
