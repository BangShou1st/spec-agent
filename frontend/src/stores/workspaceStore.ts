import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import { classifyModelFailure } from '@/api/errorCopy'
import { getProjectGraph } from '@/api/graph'
import { getProject } from '@/api/projects'
import { getRequirementState, getRouteRequirementState } from '@/api/requirementState'
import {
  activateRoute,
  archiveRoute,
  deleteRoute,
  forkNode,
  reanswerNode,
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
  repairAnswer,
  submitAnswer,
} from '@/api/workspace'

export interface DisplayError {
  code: string
  message: string
  status?: number
}

/** Precise route command in flight, used for pending labels and lockouts. */
export type PendingRouteCommand =
  | 'activate'
  | 'restore'
  | 'archive'
  | 'delete'
  | 'fork'
  | 'reanswer'
  | 'regenerate'
  | null

type RetryState = 'ready' | 'needs_reconcile' | 'ambiguous'

type ManualModelRetryIntent =
  | {
      kind: 'draft'
      beforeRouteId: string | null
      beforeTipNodeId: string | null
      state: RetryState
    }
  | {
      kind: 'spec'
      routeId: string
      beforeSpecIds: string[]
      state: RetryState
    }
  | {
      kind: 'regenerate'
      nodeId: string
      payload: RegenerateNodeRequest
      beforeRouteIds: string[]
      beforeActiveRouteId: string | null
      state: RetryState
    }

type MutationFocusTarget = {
  routeId: string
  nodeId: string | null
}

function toDisplayError(err: unknown): DisplayError {
  if (err instanceof ApiError) {
    return { code: err.code, message: err.message, status: err.status }
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
    repairingAnswer: false,
    feedback: null as string | null,
    error: null as DisplayError | null,
    repairableAnswerId: null as string | null,
    resubmitAnswerPayload: null as SubmitAnswerRequest | null,
    pendingAnswerNodeId: null as string | null,
    answerOutcomeUnknown: false,
    manualModelRetry: null as ManualModelRetryIntent | null,
    focusAfterMutation: null as MutationFocusTarget | null,

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
    /** Fork is durable even when its follow-up Draft command fails. */
    forkDraftRetryRouteId: null as string | null,

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
      this.repairableAnswerId = null
      this.resubmitAnswerPayload = null
      this.pendingAnswerNodeId = null
      this.answerOutcomeUnknown = false
      this.manualModelRetry = null
      this.forkDraftRetryRouteId = null
      this.focusAfterMutation = null
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
        this.restoreCanonicalRecoveryCheckpoints()
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
    async refreshWorkspace(): Promise<boolean> {
      if (!this.projectId || this.refreshing) {
        return false
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
        this.restoreCanonicalRecoveryCheckpoints()
        // RequirementState is derived and answers/patches change it: drop the
        // route-scoped cache on every canonical refresh so the reading UI
        // reloads it from the backend.
        this.requirementStatesByRoute = {}
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.refreshing = false
      }
    },

    /** Rebuilds recovery affordances from canonical reads after reload/refresh. */
    restoreCanonicalRecoveryCheckpoints(): void {
      this.repairableAnswerId = this.findFinalizedAnswerForActiveTip()
      this.forkDraftRetryRouteId = this.findForkDraftRetryRouteId()
    },

    /** Drafts the next question. Explicit user action only. */
    async draftQuestion(): Promise<boolean> {
      if (!this.projectId || this.drafting || this.routeCommandPending) {
        return false
      }
      this.drafting = true
      this.error = null
      const beforeRouteId = this.activeState?.activeRoute?.id ?? null
      const beforeTipNodeId = this.activeState?.activeRoute?.tipNodeId ?? null
      try {
        await draftNextQuestion(this.projectId)
        this.feedback = '问题已起草。'
        await this.refreshWorkspace()
        this.manualModelRetry = null
        return true
      } catch (err) {
        const safeError = toDisplayError(err)
        this.error = safeError
        const disposition = classifyModelFailure(safeError.code, safeError.status)
        const retryIntent = disposition === 'none' ? null : {
          kind: 'draft',
          beforeRouteId,
          beforeTipNodeId,
          state: disposition === 'unknown' ? 'needs_reconcile' : 'ready',
        } as ManualModelRetryIntent
        this.manualModelRetry = retryIntent
        if (retryIntent?.kind === 'draft' && retryIntent.state === 'needs_reconcile') {
          const reconciled = await this.refreshWorkspace()
          const afterRouteId = this.activeState?.activeRoute?.id ?? null
          const afterTipNodeId = this.activeState?.activeRoute?.tipNodeId ?? null
          if (
            reconciled
            && (afterRouteId !== beforeRouteId
              || afterTipNodeId !== beforeTipNodeId)
          ) {
            this.manualModelRetry = null
            this.error = null
            this.feedback = '问题已起草。'
            return true
          } else {
            this.error = safeError
          }
        }
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
      this.repairableAnswerId = null
      this.resubmitAnswerPayload = null
      this.pendingAnswerNodeId = this.activeState?.activeNode?.id
        ?? this.activeState?.activeRoute?.tipNodeId
        ?? null
      this.answerOutcomeUnknown = false
      try {
        await submitAnswer(this.projectId, payload)
        this.feedback = '回答已记录。'
        await this.refreshWorkspace()
        this.manualModelRetry = null
        return true
      } catch (err) {
        const safeError = toDisplayError(err)
        this.resubmitAnswerPayload = { ...payload }
        const reconciled = await this.refreshWorkspace()
        let canonicalMutationCompleted = false
        if (!reconciled) {
          this.answerOutcomeUnknown = true
        } else {
          const answerId = this.findFinalizedAnswerForNode(this.pendingAnswerNodeId)
          if (answerId) {
            if (this.activeState?.activeRoute?.tipNodeId === this.pendingAnswerNodeId) {
              this.repairableAnswerId = answerId
              this.resubmitAnswerPayload = null
              this.feedback = '回答已保存，后续生成未完成。'
            } else {
              // The canonical tip moved past the submitted node: the lost
              // response was for a completed mutation, so never resubmit it.
              this.repairableAnswerId = null
              this.resubmitAnswerPayload = null
              this.pendingAnswerNodeId = null
              this.feedback = '回答已记录。'
              this.error = null
              canonicalMutationCompleted = true
            }
          }
        }
        if (!canonicalMutationCompleted) this.error = safeError
        return false
      } finally {
        this.submitting = false
      }
    },

    /** Reconciles canonical state before allowing a failed submit to mutate again. */
    async reconcileAnswerOutcome(): Promise<boolean> {
      const payload = this.resubmitAnswerPayload
      if (!payload && !this.answerOutcomeUnknown) return false
      const previousError = this.error
      const reconciled = await this.refreshWorkspace()
      if (!reconciled) {
        this.answerOutcomeUnknown = true
        this.error = previousError
        return false
      }
      const answerId = this.findFinalizedAnswerForNode(this.pendingAnswerNodeId)
      this.answerOutcomeUnknown = false
      if (answerId) {
        if (this.activeState?.activeRoute?.tipNodeId === this.pendingAnswerNodeId) {
          this.repairableAnswerId = answerId
          this.resubmitAnswerPayload = null
          this.feedback = '回答已保存，后续生成未完成。'
        } else {
          this.repairableAnswerId = null
          this.resubmitAnswerPayload = null
          this.pendingAnswerNodeId = null
          this.feedback = '回答已记录。'
        }
      } else {
        this.repairableAnswerId = null
        this.resubmitAnswerPayload = payload
      }
      this.error = previousError
      return true
    },

    /** Repairs an existing answer checkpoint; it never creates a second Answer. */
    async repairAnswerForActiveFlow(answerId: string): Promise<boolean> {
      if (!this.projectId || this.repairingAnswer || this.routeCommandPending) return false
      this.repairingAnswer = true
      this.error = null
      try {
        await repairAnswer(this.projectId, answerId)
        this.repairableAnswerId = null
        this.resubmitAnswerPayload = null
        this.pendingAnswerNodeId = null
        this.answerOutcomeUnknown = false
        this.feedback = '已重新请求后续生成。'
        await this.refreshWorkspace()
        return true
      } catch (err) {
        const safeError = toDisplayError(err)
        const reconciled = await this.refreshWorkspace()
        if (reconciled) {
          this.repairableAnswerId = this.findFinalizedAnswerForActiveTip()
        }
        this.error = safeError
        return false
      } finally {
        this.repairingAnswer = false
      }
    },

    /** Re-submits only after reconciliation proved that the Answer was absent. */
    async resubmitFailedAnswer(): Promise<boolean> {
      const payload = this.resubmitAnswerPayload
      if (!payload || !this.projectId || this.submitting || this.routeCommandPending) return false
      return this.submitAnswer(payload)
    },

    findFinalizedAnswerForActiveTip(): string | null {
      const activeRoute = this.activeState?.activeRoute
      const tipNodeId = activeRoute?.tipNodeId
      if (!activeRoute || !tipNodeId) return null
      return this.graphView?.answers.find((answer) =>
        answer.nodeId === tipNodeId
        && answer.routeId === activeRoute.id
        && answer.inherited === false
        && answer.ownerRouteId === activeRoute.id,
      )?.id ?? null
    },

    findFinalizedAnswerForNode(nodeId: string | null): string | null {
      const activeRoute = this.activeState?.activeRoute
      if (!activeRoute || !nodeId) return null
      return this.graphView?.answers.find((answer) =>
        answer.routeId === activeRoute.id
        && answer.nodeId === nodeId
        && answer.inherited === false
        && answer.ownerRouteId === activeRoute.id,
      )?.id ?? null
    },

    findForkDraftRetryRouteId(): string | null {
      const activeRoute = this.activeState?.activeRoute
      const graphRoute = activeRoute
        ? this.graphView?.routes.find((route) => route.id === activeRoute.id)
        : null
      const tipNodeId = graphRoute?.tipNodeId ?? activeRoute?.tipNodeId
      if (
        !activeRoute
        || !graphRoute
        || graphRoute.branchType !== 'fork'
        || !tipNodeId
        || graphRoute.branchAtNodeId !== tipNodeId
      ) {
        return null
      }
      const tipAnswers = this.graphView?.answers.filter((answer) =>
        answer.routeId === graphRoute.id && answer.nodeId === tipNodeId,
      ) ?? []
      return tipAnswers.length === 1
        && tipAnswers[0].inherited === true
        && tipAnswers[0].ownerRouteId !== graphRoute.id
        ? graphRoute.id
        : null
    },

    setFocusAfterMutation(target: MutationFocusTarget | null): void {
      this.focusAfterMutation = target
    },

    consumeFocusAfterMutation(): MutationFocusTarget | null {
      const target = this.focusAfterMutation
      this.focusAfterMutation = null
      return target
    },

    async retryManualModelOperation(): Promise<boolean> {
      const intent = this.manualModelRetry
      if (!intent) return false
      if (intent.state === 'ambiguous') {
        const previousError = this.error
        await this.refreshWorkspace()
        this.error = previousError
        return false
      }
      if (intent.state === 'needs_reconcile') {
        const previousError = this.error
        if (intent.kind === 'draft') {
          const reconciled = await this.refreshWorkspace()
          const afterRouteId = this.activeState?.activeRoute?.id ?? null
          const afterTipNodeId = this.activeState?.activeRoute?.tipNodeId ?? null
          if (!reconciled) {
            this.error = previousError
            return false
          }
          if (
            afterRouteId !== intent.beforeRouteId
            || afterTipNodeId !== intent.beforeTipNodeId
          ) {
            this.manualModelRetry = null
            this.error = null
            this.feedback = '问题已起草。'
            return true
          }
          this.manualModelRetry = { ...intent, state: 'ready' }
          this.error = previousError
          return false
        }
        if (intent.kind === 'spec') return this.reconcileSpecRetry(intent)
        return this.reconcileRegenerateRetry(intent)
      }
      if (intent.kind === 'draft') return this.draftQuestion()
      if (intent.kind === 'spec') {
        const result = await this.generateSpec()
        return result !== null || (
          this.manualModelRetry === null && this.feedback === '已生成规格快照。'
        )
      }
      return this.regenerateNode(intent.nodeId, intent.payload)
    },

    async reconcileRegenerateRetry(intent: Extract<ManualModelRetryIntent, { kind: 'regenerate' }>): Promise<boolean> {
      const previousError = this.error
      const reconciled = await this.refreshWorkspace()
      if (!reconciled) {
        this.error = previousError
        return false
      }
      const afterRoutes = this.graphView?.routes ?? []
      const newRoutes = afterRoutes.filter((route) => !intent.beforeRouteIds.includes(route.id))
      const matchingRoutes = newRoutes.filter((route) =>
        route.branchType === 'regenerate'
        && route.sourceRouteId === intent.payload.sourceRouteId
        && route.branchAtNodeId === intent.nodeId
        && route.replacementOfNodeId === intent.nodeId,
      )
      const activeRouteId = this.activeState?.activeRoute?.id ?? null
      if (matchingRoutes.length === 1 && activeRouteId === matchingRoutes[0].id) {
        this.manualModelRetry = null
        this.error = null
        this.feedback = '已创建换一个问题路线。'
        this.setFocusAfterMutation({
          routeId: matchingRoutes[0].id,
          nodeId: matchingRoutes[0].tipNodeId,
        })
        return true
      }
      if (matchingRoutes.length === 0 && activeRouteId === intent.beforeActiveRouteId) {
        this.manualModelRetry = { ...intent, state: 'ready' }
        this.error = previousError
        return false
      }
      this.manualModelRetry = { ...intent, state: 'ambiguous' }
      this.error = {
        code: 'RECOVERY_AMBIGUOUS',
        message: '请求结果无法安全确认，请刷新状态后人工核对。',
      }
      return false
    },

    async reconcileSpecRetry(intent: Extract<ManualModelRetryIntent, { kind: 'spec' }>): Promise<boolean> {
      const previousError = this.error
      const reconciled = await this.refreshWorkspace()
      if (!reconciled) {
        this.error = previousError
        return false
      }
      if (this.activeState?.activeRoute?.id !== intent.routeId) {
        this.manualModelRetry = { ...intent, state: 'ambiguous' }
        this.error = {
          code: 'RECOVERY_AMBIGUOUS',
          message: '请求结果无法安全确认，请刷新状态后人工核对。',
        }
        return false
      }
      let specs: SpecSnapshotResponse[]
      try {
        specs = await listRouteSpecs(this.projectId!, intent.routeId)
      } catch {
        this.error = previousError
        return false
      }
      this.specsByRoute = { ...this.specsByRoute, [intent.routeId]: specs }
      const newSpecs = specs.filter((snapshot) => !intent.beforeSpecIds.includes(snapshot.id))
      if (newSpecs.length === 1) {
        this.selectedSpecIdByRoute = {
          ...this.selectedSpecIdByRoute,
          [intent.routeId]: newSpecs[0].id,
        }
        this.manualModelRetry = null
        this.error = null
        this.feedback = '已生成规格快照。'
        return true
      }
      if (newSpecs.length === 0) {
        this.manualModelRetry = { ...intent, state: 'ready' }
        this.error = previousError
        return false
      }
      this.manualModelRetry = { ...intent, state: 'ambiguous' }
      this.error = {
        code: 'RECOVERY_AMBIGUOUS',
        message: '请求结果无法安全确认，请刷新状态后人工核对。',
      }
      return false
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
    async forkNode(nodeId: string, sourceRouteId: string, label?: string | null): Promise<boolean> {
      if (!this.projectId || this.routeCommandPending || this.submitting || this.drafting) {
        return false
      }
      if (!sourceRouteId) {
        this.error = { code: 'SOURCE_ROUTE_REQUIRED', message: '请选择明确的来源路线。' }
        return false
      }
      this.routeCommandPending = true
      this.pendingRouteCommand = 'fork'
      this.error = null
      this.forkDraftRetryRouteId = null
      try {
        const result = await forkNode(this.projectId, nodeId, {
          sourceRouteId,
          label: label ?? null,
        })
        await this.refreshWorkspace()
        // Fork and first-child Draft are separate Runtime commands. The
        // route is intentionally preserved if Draft fails.
        this.routeCommandPending = false
        this.pendingRouteCommand = null
        const drafted = await this.draftQuestion()
        if (!drafted) {
          this.forkDraftRetryRouteId = result.route.id
          this.setFocusAfterMutation({
            routeId: result.route.id,
            nodeId: this.activeState?.activeRoute?.tipNodeId ?? result.route.tipNodeId,
          })
          this.feedback = '分支已创建，但首个后续问题起草失败，可重试。'
          return false
        }
        this.setFocusAfterMutation({
          routeId: result.route.id,
          nodeId: this.activeState?.activeRoute?.tipNodeId ?? result.route.tipNodeId,
        })
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

    async retryForkDraft(): Promise<boolean> {
      const retryRouteId = this.forkDraftRetryRouteId
      if (!retryRouteId || this.routeCommandPending || this.drafting) {
        return false
      }
      const activeRoute = this.activeState?.activeRoute
      const retryRoute = this.graphView?.routes.find((route) => route.id === retryRouteId)
      if (activeRoute?.id !== retryRouteId || retryRoute?.lifecycleStatus !== 'open') {
        this.error = {
          code: 'FORK_DRAFT_RETRY_REQUIRES_ACTIVE_ROUTE',
          message: '请先将该分支设为当前路线，再重试起草。',
        }
        return false
      }
      const drafted = this.manualModelRetry?.kind === 'draft'
        ? await this.retryManualModelOperation()
        : await this.draftQuestion()
      if (drafted) {
        this.forkDraftRetryRouteId = null
        this.setFocusAfterMutation({
          routeId: retryRouteId,
          nodeId: this.activeState?.activeRoute?.tipNodeId ?? null,
        })
        this.feedback = '已起草分支的首个后续问题。'
      }
      return drafted
    },

    async reanswerNode(nodeId: string, sourceRouteId: string, label?: string | null): Promise<boolean> {
      if (!this.projectId || this.routeCommandPending || this.submitting || this.drafting) {
        return false
      }
      this.routeCommandPending = true
      this.pendingRouteCommand = 'reanswer'
      this.error = null
      try {
        await reanswerNode(this.projectId, nodeId, { sourceRouteId, label: label ?? null })
        await this.refreshWorkspace()
        this.feedback = '已创建重新回答路线。'
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
      const beforeRouteIds = this.graphView?.routes.map((route) => route.id) ?? []
      const beforeActiveRouteId = this.activeState?.activeRoute?.id ?? null
      // The integrated dialog supplies the explicit sourceRouteId required by
      // the Runtime contract; no compatibility payload is synthesized here.
      try {
        const result = await regenerateNodeCommand(this.projectId, nodeId, payload)
        await this.refreshWorkspace()
        this.feedback = '已创建换一个问题路线。'
        this.manualModelRetry = null
        this.setFocusAfterMutation({
          routeId: result.replacementRoute.id,
          nodeId: result.replacementNode.id,
        })
        return true
      } catch (err) {
        const safeError = toDisplayError(err)
        this.error = safeError
        const disposition = classifyModelFailure(safeError.code, safeError.status)
        if (disposition === 'none') {
          this.manualModelRetry = null
          return false
        }
        const intent: Extract<ManualModelRetryIntent, { kind: 'regenerate' }> = {
          kind: 'regenerate',
          nodeId,
          payload: { ...payload },
          beforeRouteIds,
          beforeActiveRouteId,
          state: disposition === 'unknown' ? 'needs_reconcile' : 'ready',
        }
        this.manualModelRetry = intent
        if (intent.state === 'needs_reconcile') {
          const recovered = await this.reconcileRegenerateRetry(intent)
          if (recovered) return true
        }
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
      const routeId = activeRoute.id
      let baselineSpecs: SpecSnapshotResponse[]
      try {
        // This read is the mutation baseline. If it fails, do not start a
        // generation request whose outcome could no longer be reconciled.
        baselineSpecs = await listRouteSpecs(this.projectId, routeId)
        this.specsByRoute = { ...this.specsByRoute, [routeId]: baselineSpecs }
      } catch (err) {
        this.error = toDisplayError(err)
        this.manualModelRetry = null
        return null
      }
      const beforeSpecIds = baselineSpecs.map((snapshot) => snapshot.id)
      try {
        const result = await generateSpec(this.projectId)
        const resultRouteId = result.specSnapshot.routeId
        let specs: SpecSnapshotResponse[]
        try {
          specs = await listRouteSpecs(this.projectId, resultRouteId)
        } catch {
          // The command already returned a durable artifact. Preserve it in
          // the local read cache without issuing another generation request.
          specs = [
            ...baselineSpecs.filter((snapshot) => snapshot.id !== result.specSnapshot.id),
            result.specSnapshot,
          ]
        }
        this.specsByRoute = { ...this.specsByRoute, [resultRouteId]: specs }
        this.selectedSpecIdByRoute = {
          ...this.selectedSpecIdByRoute,
          [resultRouteId]: result.specSnapshot.id,
        }
        this.feedback = '已生成规格快照。'
        this.manualModelRetry = null
        return result
      } catch (err) {
        const safeError = toDisplayError(err)
        this.error = safeError
        const disposition = classifyModelFailure(safeError.code, safeError.status)
        if (disposition === 'none') {
          this.manualModelRetry = null
          return null
        }
        const intent: Extract<ManualModelRetryIntent, { kind: 'spec' }> = {
          kind: 'spec',
          routeId,
          beforeSpecIds,
          state: disposition === 'unknown' ? 'needs_reconcile' : 'ready',
        }
        this.manualModelRetry = intent
        if (intent.state === 'needs_reconcile') {
          await this.reconcileSpecRetry(intent)
        }
        return null
      } finally {
        this.generatingSpec = false
      }
    },
  },
})
