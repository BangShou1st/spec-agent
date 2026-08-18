import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import { getProject } from '@/api/projects'
import { getRequirementState } from '@/api/requirementState'
import {
  activateRoute,
  archiveRoute,
  deleteRoute,
  forkNode,
  getRouteLineage,
  regenerateNode as regenerateNodeCommand,
  restoreRoute,
} from '@/api/routes'
import { generateSpec, listRouteSpecs } from '@/api/spec'
import type {
  ActiveProjectStateResponse,
  ProjectResponse,
  RegenerateNodeRequest,
  RequirementStateView,
  RouteLineageView,
  RouteResponse,
  SpecSnapshotResponse,
  SubmitAnswerRequest,
} from '@/api/types'
import {
  draftNextQuestion,
  getActiveState,
  listRoutes,
  submitAnswer,
} from '@/api/workspace'

export interface DisplayError {
  code: string
  message: string
}

/** Precise route command in flight, used for pending labels and lockouts. */
export type PendingRouteCommand =
  | 'activate'
  | 'restore'
  | 'archive'
  | 'delete'
  | 'fork'
  | 'regenerate'
  | null

function toDisplayError(err: unknown): DisplayError {
  if (err instanceof ApiError) {
    return { code: err.code, message: err.message }
  }
  return { code: 'UNKNOWN_ERROR', message: GENERIC_ERROR_MESSAGE }
}

/**
 * Workspace application state. The frontend never reconstructs Runtime
 * history: after every command the canonical backend read APIs are refreshed
 * and this store only mirrors what the backend returned. RequirementState is
 * backend-derived and never promoted client-side. Route lineages are loaded
 * lazily from the backend route-lineage read endpoint; the frontend never
 * rebuilds an authoritative lineage itself. Route lifecycle is never mutated
 * locally — every transition goes through the existing route command API.
 */
export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    projectId: null as string | null,
    project: null as ProjectResponse | null,
    routes: [] as RouteResponse[],
    activeState: null as ActiveProjectStateResponse | null,
    requirementState: null as RequirementStateView | null,
    loading: false,
    refreshing: false,
    drafting: false,
    submitting: false,
    feedback: null as string | null,
    error: null as DisplayError | null,

    // Route workspace selection + lazy backend lineage cache.
    selectedRouteId: null as string | null,
    selectedNodeId: null as string | null,
    routeLineages: {} as Record<string, RouteLineageView>,
    loadingLineage: false,

    // Route command lockout: one precise command at a time.
    routeCommandPending: false,
    pendingRouteCommand: null as PendingRouteCommand,

    // Spec snapshots per selected route (backend-derived, never authored here).
    generatingSpec: false,
    loadingSpecs: false,
    specsByRoute: {} as Record<string, SpecSnapshotResponse[]>,
    selectedSpecId: null as string | null,
  }),
  getters: {
    selectedRoute(state): RouteResponse | null {
      return state.routes.find((route) => route.id === state.selectedRouteId) ?? null
    },
    selectedLineage(state): RouteLineageView | null {
      if (!state.selectedRouteId) {
        return null
      }
      return state.routeLineages[state.selectedRouteId] ?? null
    },
    selectedHistoricalNode(state): RouteLineageView['nodes'][number] | null {
      if (!state.selectedNodeId) {
        return null
      }
      return this.selectedLineage?.nodes.find((node) => node.id === state.selectedNodeId) ?? null
    },
    selectedSpecs(state): SpecSnapshotResponse[] {
      if (!state.selectedRouteId) {
        return []
      }
      return state.specsByRoute[state.selectedRouteId] ?? []
    },
    selectedSpec(state): SpecSnapshotResponse | null {
      if (!state.selectedSpecId) {
        return null
      }
      return this.selectedSpecs.find((snapshot) => snapshot.id === state.selectedSpecId) ?? null
    },
    activeRoute(state): RouteResponse | null {
      return state.activeState?.activeRoute ?? null
    },
  },
  actions: {
    async loadWorkspace(projectId: string): Promise<void> {
      this.projectId = projectId
      this.loading = true
      this.error = null
      this.feedback = null
      try {
        const [project, activeState, routes, requirementState] = await Promise.all([
          getProject(projectId),
          getActiveState(projectId),
          listRoutes(projectId),
          getRequirementState(projectId),
        ])
        this.project = project
        this.activeState = activeState
        this.routes = routes
        this.requirementState = requirementState
        // The workspace opens on the backend-active route. Lineages and spec
        // snapshot lists stay lazy: they load on selection, never here.
        this.selectedRouteId = activeState.activeRoute?.id ?? null
        this.selectedNodeId = null
        this.selectedSpecId = null
        this.routeLineages = {}
        this.specsByRoute = {}
      } catch (err) {
        this.error = toDisplayError(err)
      } finally {
        this.loading = false
      }
    },

    /** Re-reads canonical backend-derived workspace views after a command. */
    async refreshWorkspace(): Promise<void> {
      if (!this.projectId || this.refreshing) {
        return
      }
      this.refreshing = true
      this.error = null
      try {
        const [project, activeState, routes, requirementState] = await Promise.all([
          getProject(this.projectId),
          getActiveState(this.projectId),
          listRoutes(this.projectId),
          getRequirementState(this.projectId),
        ])
        this.project = project
        this.activeState = activeState
        this.routes = routes
        this.requirementState = requirementState
        // Lineages are display caches and the backend is authoritative: any
        // command may have added nodes or changed route state, so the cache
        // is dropped and the selected route's lineage is reloaded from the
        // backend instead of assuming the cached shape is still current.
        this.routeLineages = {}
        if (this.selectedRouteId) {
          await this.reloadLineage(this.selectedRouteId)
        }
      } catch (err) {
        this.error = toDisplayError(err)
      } finally {
        this.refreshing = false
      }
    },

    /** Drafts the next question. Explicit user action only. */
    async draftQuestion(): Promise<boolean> {
      if (!this.projectId || this.drafting || this.routeCommandPending) {
        return false
      }
      this.drafting = true
      this.error = null
      try {
        await draftNextQuestion(this.projectId)
        this.feedback = 'Question drafted.'
        await this.refreshWorkspace()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.drafting = false
      }
    },

    /** Submits an answer; the backend owns validity, history, and next state. */
    async submitAnswer(payload: SubmitAnswerRequest): Promise<boolean> {
      if (!this.projectId || this.submitting || this.routeCommandPending) {
        return false
      }
      this.submitting = true
      this.error = null
      try {
        await submitAnswer(this.projectId, payload)
        this.feedback = 'Answer recorded.'
        await this.refreshWorkspace()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.submitting = false
      }
    },

    // ---------------- Route workspace selection ----------------

    /** Selects a route (lazy lineage/specs load on selection). */
    selectRoute(routeId: string | null): void {
      this.selectedRouteId = routeId
      this.selectedNodeId = null
      this.selectedSpecId = null
    },

    /** Selects a historical node for inspection. Nodes remain immutable. */
    selectHistoricalNode(nodeId: string): void {
      this.selectedNodeId = nodeId
    },

    /** Returns to the active clarification workflow. */
    clearHistoricalSelection(): void {
      this.selectedNodeId = null
    },

    /** Loads a route lineage from the backend unless it is already cached. */
    async ensureRouteLineage(routeId: string): Promise<void> {
      if (!this.projectId || this.routeLineages[routeId]) {
        return
      }
      await this.reloadLineage(routeId)
    },

    /** Always reloads one route lineage from the backend (canonical refresh). */
    async reloadLineage(routeId: string): Promise<void> {
      if (!this.projectId || this.loadingLineage) {
        return
      }
      this.loadingLineage = true
      try {
        const view = await getRouteLineage(this.projectId, routeId)
        this.routeLineages = { ...this.routeLineages, [routeId]: view }
      } catch (err) {
        this.error = toDisplayError(err)
      } finally {
        this.loadingLineage = false
      }
    },

    // ---------------- Route commands ----------------

    async activateRoute(routeId: string): Promise<boolean> {
      if (!this.projectId || this.routeCommandPending || this.submitting || this.drafting) {
        return false
      }
      this.routeCommandPending = true
      this.pendingRouteCommand = 'activate'
      this.error = null
      try {
        await activateRoute(this.projectId, routeId)
        await this.refreshWorkspace()
        this.selectedRouteId = routeId
        this.selectedNodeId = null
        this.selectedSpecId = null
        await this.reloadLineage(routeId)
        this.feedback = 'Route activated.'
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.routeCommandPending = false
        this.pendingRouteCommand = null
      }
    },

    async restoreRoute(routeId: string): Promise<boolean> {
      if (!this.projectId || this.routeCommandPending || this.submitting || this.drafting) {
        return false
      }
      this.routeCommandPending = true
      this.pendingRouteCommand = 'restore'
      this.error = null
      try {
        await restoreRoute(this.projectId, routeId)
        await this.refreshWorkspace()
        this.selectedRouteId = routeId
        this.selectedNodeId = null
        this.selectedSpecId = null
        await this.reloadLineage(routeId)
        this.feedback = 'Route restored.'
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.routeCommandPending = false
        this.pendingRouteCommand = null
      }
    },

    async archiveRoute(routeId: string): Promise<boolean> {
      if (!this.projectId || this.routeCommandPending || this.submitting || this.drafting) {
        return false
      }
      this.routeCommandPending = true
      this.pendingRouteCommand = 'archive'
      this.error = null
      try {
        await archiveRoute(this.projectId, routeId)
        await this.refreshWorkspace()
        this.selectedNodeId = null
        await this.reloadLineage(routeId)
        this.feedback = 'Route archived.'
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.routeCommandPending = false
        this.pendingRouteCommand = null
      }
    },

    async deleteRoute(routeId: string): Promise<boolean> {
      if (!this.projectId || this.routeCommandPending || this.submitting || this.drafting) {
        return false
      }
      this.routeCommandPending = true
      this.pendingRouteCommand = 'delete'
      this.error = null
      try {
        await deleteRoute(this.projectId, routeId)
        await this.refreshWorkspace()
        this.selectedNodeId = null
        await this.reloadLineage(routeId)
        this.feedback = 'Route deleted.'
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.routeCommandPending = false
        this.pendingRouteCommand = null
      }
    },

    /**
     * Forks a new route from a historical node. The runtime creates the new
     * route id, makes it active, and the frontend then selects the
     * backend-created active route and reloads its lineage.
     */
    async forkNode(nodeId: string, label?: string | null): Promise<boolean> {
      if (!this.projectId || this.routeCommandPending || this.submitting || this.drafting) {
        return false
      }
      this.routeCommandPending = true
      this.pendingRouteCommand = 'fork'
      this.error = null
      try {
        await forkNode(this.projectId, nodeId, { label: label ?? null })
        await this.refreshWorkspace()
        const newActive = this.activeState?.activeRoute
        if (newActive) {
          this.selectedRouteId = newActive.id
          this.selectedNodeId = null
          this.selectedSpecId = null
          await this.reloadLineage(newActive.id)
        }
        this.feedback = 'Fork created.'
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.routeCommandPending = false
        this.pendingRouteCommand = null
      }
    },

    /**
     * Deterministically regenerates a historical node. Old route becomes
     * SUPERSEDED and the replacement route becomes OPEN + active via the
     * runtime; the frontend selects the replacement route and reloads its
     * lineage instead of reconstructing the transition locally.
     */
    async regenerateNode(nodeId: string, payload: RegenerateNodeRequest): Promise<boolean> {
      if (!this.projectId || this.routeCommandPending || this.submitting || this.drafting) {
        return false
      }
      this.routeCommandPending = true
      this.pendingRouteCommand = 'regenerate'
      this.error = null
      try {
        const result = await regenerateNodeCommand(this.projectId, nodeId, payload)
        await this.refreshWorkspace()
        this.selectedRouteId = result.replacementRoute.id
        this.selectedNodeId = null
        this.selectedSpecId = null
        await this.reloadLineage(result.replacementRoute.id)
        this.feedback = 'Question regenerated.'
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.routeCommandPending = false
        this.pendingRouteCommand = null
      }
    },

    // ---------------- Spec snapshots ----------------

    /** Loads the snapshot list for a route from the backend. */
    async loadRouteSpecs(routeId: string): Promise<void> {
      if (!this.projectId) {
        return
      }
      this.loadingSpecs = true
      try {
        const specs = await listRouteSpecs(this.projectId, routeId)
        this.specsByRoute = { ...this.specsByRoute, [routeId]: specs }
      } catch (err) {
        this.error = toDisplayError(err)
      } finally {
        this.loadingSpecs = false
      }
    },

    selectSpec(snapshotId: string | null): void {
      this.selectedSpecId = snapshotId
    },

    /**
     * Generates a spec snapshot for the ACTIVE route through the backend.
     * After success the canonical snapshot list is reloaded and the new
     * snapshot is selected; the frontend never synthesizes a spec locally.
     */
    async generateSpec(): Promise<boolean> {
      if (!this.projectId || this.generatingSpec || this.routeCommandPending) {
        return false
      }
      const activeRoute = this.activeState?.activeRoute
      if (!activeRoute || !activeRoute.tipNodeId) {
        this.error = {
          code: 'NO_ACTIVE_TIP_NODE',
          message: 'The active route has no tip node to generate a spec from.',
        }
        return false
      }
      this.generatingSpec = true
      this.error = null
      try {
        const result = await generateSpec(this.projectId)
        await this.loadRouteSpecs(result.specSnapshot.routeId)
        this.selectedSpecId = result.specSnapshot.id
        this.feedback = 'Spec snapshot generated.'
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.generatingSpec = false
      }
    },
  },
})