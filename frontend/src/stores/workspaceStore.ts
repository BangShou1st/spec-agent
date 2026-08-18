import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import { getProjectGraph } from '@/api/graph'
import { getProject } from '@/api/projects'
import { getRequirementState, getRouteRequirementState } from '@/api/requirementState'
import {
  activateRoute,
  archiveRoute,
  deleteRoute,
  forkNode,
  regenerateNode as regenerateNodeCommand,
  restoreRoute,
} from '@/api/routes'
import { generateSpec, listRouteSpecs } from '@/api/spec'
import type {
  ActiveProjectStateResponse,
  GraphWorkspaceView,
  ProjectResponse,
  RegenerateNodeRequest,
  RequirementStateView,
  RouteResponse,
  SpecGenerationResponse,
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
 * Workspace application state (canonical server state + Runtime commands).
 *
 * The frontend never reconstructs Runtime history: after every command the
 * canonical backend read APIs are refreshed and this store only mirrors what
 * the backend returned. RequirementState is backend-derived and never
 * promoted client-side. Route lifecycle is never mutated locally — every
 * transition goes through the existing route command API.
 *
 * Browser-only view state (selection, focus, filters, layout, sidebars)
 * lives in `graphUiStore`; this store never imports it and never lets Focus
 * change command targeting — draft/submit/spec generation always target the
 * backend Active route.
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

    // Canonical graph read (Phase 7.3A): replaced from the backend on every
    // refresh; the frontend never patches it locally.
    graphView: null as GraphWorkspaceView | null,
    // Route-scoped requirement-state cache, indexed by explicit route id.
    requirementStatesByRoute: {} as Record<string, RequirementStateView>,
    loadingRequirementRouteId: null as string | null,
    // Route-scoped spec selection, indexed by explicit route id.
    selectedSpecIdByRoute: {} as Record<string, string | null>,

    // Route command lockout: one precise command at a time.
    routeCommandPending: false,
    pendingRouteCommand: null as PendingRouteCommand,

    // Spec snapshots per route (backend-derived, never authored here).
    generatingSpec: false,
    loadingSpecs: false,
    specsByRoute: {} as Record<string, SpecSnapshotResponse[]>,
  }),
  getters: {
    activeRoute(state): RouteResponse | null {
      return state.activeState?.activeRoute ?? null
    },
    /** Resolves the selected snapshot for one explicit route. */
    selectedSpecForRoute(): (routeId: string) => SpecSnapshotResponse | null {
      return (routeId: string) => {
        const id = this.selectedSpecIdByRoute[routeId]
        if (!id) {
          return null
        }
        return (this.specsByRoute[routeId] ?? []).find((snapshot) => snapshot.id === id) ?? null
      }
    },
  },
  actions: {
    async loadWorkspace(projectId: string): Promise<void> {
      this.projectId = projectId
      this.loading = true
      this.error = null
      this.feedback = null
      try {
        const [project, activeState, routes, requirementState, graphView] = await Promise.all([
          getProject(projectId),
          getActiveState(projectId),
          listRoutes(projectId),
          getRequirementState(projectId),
          getProjectGraph(projectId),
        ])
        this.project = project
        this.activeState = activeState
        this.routes = routes
        this.requirementState = requirementState
        this.graphView = graphView
        this.requirementStatesByRoute = {}
        this.specsByRoute = {}
        this.selectedSpecIdByRoute = {}
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
        const [project, activeState, routes, requirementState, graphView] = await Promise.all([
          getProject(this.projectId),
          getActiveState(this.projectId),
          listRoutes(this.projectId),
          getRequirementState(this.projectId),
          getProjectGraph(this.projectId),
        ])
        this.project = project
        this.activeState = activeState
        this.routes = routes
        this.requirementState = requirementState
        this.graphView = graphView
        // RequirementState is derived and answers/patches change it: drop the
        // route-scoped cache on every canonical refresh so the reading UI
        // reloads it from the backend.
        this.requirementStatesByRoute = {}
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
        this.feedback = '问题已起草。'
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
        this.feedback = '回答已记录。'
        await this.refreshWorkspace()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.submitting = false
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
        this.feedback = '已设为当前路线。'
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
        this.feedback = '已恢复路线。'
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
        this.feedback = '已归档路线。'
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
        this.feedback = '已删除路线。'
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
     * route id and makes it active; the frontend then refreshes canonical
     * reads and never guesses the new route id.
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
        this.feedback = '已创建新分支路线。'
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
     * runtime; the frontend refreshes canonical reads instead of
     * reconstructing the transition locally.
     */
    async regenerateNode(nodeId: string, payload: RegenerateNodeRequest): Promise<boolean> {
      if (!this.projectId || this.routeCommandPending || this.submitting || this.drafting) {
        return false
      }
      this.routeCommandPending = true
      this.pendingRouteCommand = 'regenerate'
      this.error = null
      try {
        await regenerateNodeCommand(this.projectId, nodeId, payload)
        await this.refreshWorkspace()
        this.feedback = '已创建替代问题路线。'
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.routeCommandPending = false
        this.pendingRouteCommand = null
      }
    },

    // ---------------- Route-scoped reads ----------------

    /**
     * Loads (and caches) the requirement state for an explicit route. The
     * cache is indexed by route id; no global selection decides ownership.
     */
    async ensureRequirementState(routeId: string): Promise<RequirementStateView | null> {
      if (!this.projectId) {
        return null
      }
      const cached = this.requirementStatesByRoute[routeId]
      if (cached) {
        return cached
      }
      this.loadingRequirementRouteId = routeId
      try {
        const state = await getRouteRequirementState(this.projectId, routeId)
        this.requirementStatesByRoute = {
          ...this.requirementStatesByRoute,
          [routeId]: state,
        }
        return state
      } catch (err) {
        this.error = toDisplayError(err)
        return null
      } finally {
        this.loadingRequirementRouteId = null
      }
    },

    /** Selects the displayed spec snapshot for one explicit route. */
    selectSpecForRoute(routeId: string, snapshotId: string | null): void {
      this.selectedSpecIdByRoute = {
        ...this.selectedSpecIdByRoute,
        [routeId]: snapshotId,
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

    /**
     * Generates a spec snapshot for the ACTIVE route through the backend.
     * After success the canonical snapshot list is reloaded and the new
     * snapshot is selected in that route's cache; the frontend never
     * synthesizes a spec locally and never sets Focus here. Returns the
     * backend artifact so the shell can follow the returned route.
     */
    async generateSpec(): Promise<SpecGenerationResponse | null> {
      if (!this.projectId || this.generatingSpec || this.routeCommandPending) {
        return null
      }
      const activeRoute = this.activeState?.activeRoute
      if (!activeRoute || !activeRoute.tipNodeId) {
        this.error = {
          code: 'NO_ACTIVE_TIP_NODE',
          message: 'The active route has no tip node to generate a spec from.',
        }
        return null
      }
      this.generatingSpec = true
      this.error = null
      try {
        const result = await generateSpec(this.projectId)
        const routeId = result.specSnapshot.routeId
        await this.loadRouteSpecs(routeId)
        this.selectedSpecIdByRoute = {
          ...this.selectedSpecIdByRoute,
          [routeId]: result.specSnapshot.id,
        }
        this.feedback = '已生成规格快照。'
        return result
      } catch (err) {
        this.error = toDisplayError(err)
        return null
      } finally {
        this.generatingSpec = false
      }
    },
  },
})