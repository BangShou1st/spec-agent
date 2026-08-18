import { defineStore } from 'pinia'
import type { RouteLifecycleStatus } from '@/api/types'
import {
  LEFT_SIDEBAR_RANGE,
  RIGHT_SIDEBAR_RANGE,
  loadProjectGraphPreferences,
  loadWorkspaceUiPreferences,
  saveProjectGraphPreferences,
  saveWorkspaceUiPreferences,
} from '@/graph/graphLayoutStorage'
import type {
  GraphPosition,
  GraphRouteDisplayState,
  ProjectGraphPreferencesV1,
  WorkspaceUiPreferencesV1,
} from '@/graph/graphTypes'

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
 * Focus Route is a reading context only: `readingRouteId(activeRouteId)`
 * prefers the focused route but never changes the Active route.
 *
 * Persisted locally: per-project node positions + route display states,
 * and the global sidebar open/width preferences. Never persisted:
 * selection, expanded nodes, focus, pan/zoom.
 */
export const useGraphUiStore = defineStore('graphUi', {
  state: () => {
    const workspaceUi = loadWorkspaceUiPreferences()
    return {
      projectId: null as string | null,
      activeRouteId: null as string | null,
      selectedNodeIds: [] as string[],
      primarySelectedNodeId: null as string | null,
      focusRouteId: null as string | null,
      lifecycleFilters: { ...DEFAULT_FILTERS } as Record<RouteLifecycleStatus, boolean>,
      routeDisplayStates: {} as Record<string, GraphRouteDisplayState>,
      expandedNodeIds: [] as string[],
      nodePositions: {} as Record<string, GraphPosition>,
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
      const prefs = loadProjectGraphPreferences(projectId)
      this.nodePositions = { ...prefs.nodePositions }
      this.routeDisplayStates = { ...prefs.routeDisplayStates }
    },

    /** readingRouteId = focusRouteId ?? activeRouteId (browser-only). */
    readingRouteId(activeRouteId: string | null): string | null {
      return this.focusRouteId ?? activeRouteId
    },

    /** Normal click: single selection replaces the old selection. */
    selectNode(nodeId: string): void {
      this.selectedNodeIds = [nodeId]
      this.primarySelectedNodeId = nodeId
    },

    /** Ctrl/Cmd + click: add/remove from multi-selection. */
    toggleSelectNode(nodeId: string): void {
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
      if (!this.primarySelectedNodeId || !nodeIds.includes(this.primarySelectedNodeId)) {
        this.primarySelectedNodeId = nodeIds[0] ?? null
      }
    },

    clearSelection(): void {
      this.selectedNodeIds = []
      this.primarySelectedNodeId = null
    },

    setFocusRoute(routeId: string | null): void {
      this.focusRouteId = routeId
    },

    clearFocusRoute(): void {
      this.focusRouteId = null
    },

    setLifecycleFilter(status: RouteLifecycleStatus, visible: boolean): void {
      this.lifecycleFilters = { ...this.lifecycleFilters, [status]: visible }
    },

    /**
     * Hide keeps route-exclusive elements out of the current browser view.
     * The Active route can never be hidden.
     */
    hideRoute(routeId: string): void {
      if (routeId === this.activeRouteId) {
        return
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

    /**
     * Clears Focus and all manual dim/hide but preserves lifecycle filters.
     */
    showAll(): void {
      this.focusRouteId = null
      this.routeDisplayStates = {}
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
          focusRoute !== undefined && this.lifecycleFilters[focusRoute.lifecycleStatus] === true
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
    },

    persistWorkspaceState(): void {
      const prefs: WorkspaceUiPreferencesV1 = {
        version: 1,
        leftSidebar: { open: this.leftSidebarOpen, width: this.leftSidebarWidth },
        rightSidebar: { open: this.rightSidebarOpen, width: this.rightSidebarWidth },
      }
      saveWorkspaceUiPreferences(prefs)
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