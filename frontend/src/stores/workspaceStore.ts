import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import { classifyModelFailure } from '@/api/errorCopy'
import {
  AGENT_RUN_MAX_POLLS,
  AGENT_RUN_POLL_INTERVAL_MS,
  createAgentRun,
  getAgentRun,
  isTerminalRunStatus,
} from '@/api/agentRuns'
import type { AgentRunView } from '@/api/agentRuns'
import {
  appendContinuation,
  attachResource as attachResourceCommand,
  createNodeQuery,
  createRootDraftNode,
  getNodeQueryResult,
  getUndoRedoAvailability,
  redoGraphOperation,
  reviseDraftNode,
  setKnowledgeStatus,
  undoGraphOperation,
} from '@/api/graphCommands'
import { getProjectGraph } from '@/api/graph'
import { getProject } from '@/api/projects'
import { getRequirementState, getRouteRequirementState } from '@/api/requirementState'
import {
  activateRoute,
  archiveRoute,
  deleteRoute,
  forkNode,
  reanswerNode,
  restoreRoute,
} from '@/api/routes'
import { listRouteSpecs } from '@/api/spec'
import type {
  ActiveProjectStateResponse,
  GraphWorkspaceView,
  ProjectResponse,
  RegenerateNodeRequest,
  RequirementStateView,
  RouteResponse,
  SpecSnapshotResponse,
  SubmitAnswerRequest,
} from '@/api/types'
import {
  getActiveState,
  listRoutes,
} from '@/api/workspace'
import { useInputDraftStore } from '@/stores/inputDraftStore'

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
    /** In-flight answer run (async Runtime); null when no run is being polled. */
    answerRunId: null as string | null,
    /** Latest observed phase of the in-flight answer run. */
    answerRunPhase: null as string | null,
    /** Last payload handed to submitAnswer; used only for proven-safe resubmit. */
    lastSubmittedAnswerPayload: null as SubmitAnswerRequest | null,
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

    // Graph workspace commands: one mutation in flight at a time.
    graphCommandPending: false,
    undoRedo: { canUndo: false, canRedo: false } as { canUndo: boolean; canRedo: boolean },
    // In-flight / finished contextual node query ("ask AI about this node").
    nodeQuery: null as {
      nodeId: string
      routeId: string
      question: string
      runId: string
      status: 'RUNNING' | 'COMPLETED' | 'FAILED'
      message: string | null
    } | null,
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
      this.answerRunId = null
      this.answerRunPhase = null
      this.lastSubmittedAnswerPayload = null
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

    /**
     * Drafts the next question through the async Agent Runtime. Explicit user
     * action only; a fresh project enqueues no run until this fires.
     */
    async draftQuestion(): Promise<boolean> {
      if (!this.projectId || this.drafting || this.routeCommandPending) {
        return false
      }
      this.drafting = true
      this.error = null
      const beforeRouteId = this.activeState?.activeRoute?.id ?? null
      const beforeTipNodeId = this.activeState?.activeRoute?.tipNodeId ?? null
      try {
        const run = await createAgentRun(this.projectId, { operation: 'DRAFT_QUESTION' })
        const outcome = await this.pollDraftRun(run.runId)
        if (outcome === 'completed') {
          this.feedback = '问题已起草。'
          await this.refreshWorkspace()
          this.manualModelRetry = null
          return true
        }
        // FAILED or outcome unknown: reconcile against canonical reads, then
        // surface the retry affordance keyed to the pre-draft graph state.
        const reconciled = await this.refreshWorkspace()
        const afterRouteId = this.activeState?.activeRoute?.id ?? null
        const afterTipNodeId = this.activeState?.activeRoute?.tipNodeId ?? null
        if (
          reconciled
          && (afterRouteId !== beforeRouteId || afterTipNodeId !== beforeTipNodeId)
        ) {
          // The draft actually landed (e.g. the run finished after the last
          // poll); never offer a retry that would double-draft.
          this.manualModelRetry = null
          this.error = null
          this.feedback = '问题已起草。'
          return true
        }
        this.error = {
          code: outcome === 'failed' ? 'AGENT_RUN_FAILED' : 'AGENT_RUN_OUTCOME_UNKNOWN',
          message: outcome === 'failed'
            ? '起草问题的运行失败，请重试。'
            : '起草结果未知，已按最新状态核对。请重试。',
        }
        this.manualModelRetry = {
          kind: 'draft',
          beforeRouteId,
          beforeTipNodeId,
          state: outcome === 'failed' ? 'ready' : 'needs_reconcile',
        } as ManualModelRetryIntent
        return false
      } catch (err) {
        // The create-run request itself failed; the run may or may not exist.
        // Reconcile canonical state before allowing a retry.
        const safeError = toDisplayError(err)
        this.error = safeError
        const reconciled = await this.refreshWorkspace()
        const afterRouteId = this.activeState?.activeRoute?.id ?? null
        const afterTipNodeId = this.activeState?.activeRoute?.tipNodeId ?? null
        if (
          reconciled
          && (afterRouteId !== beforeRouteId || afterTipNodeId !== beforeTipNodeId)
        ) {
          this.manualModelRetry = null
          this.error = null
          this.feedback = '问题已起草。'
          return true
        }
        const disposition = classifyModelFailure(safeError.code, safeError.status)
        this.manualModelRetry = disposition === 'none' ? null : {
          kind: 'draft',
          beforeRouteId,
          beforeTipNodeId,
          state: disposition === 'unknown' ? 'needs_reconcile' : 'ready',
        } as ManualModelRetryIntent
        return false
      } finally {
        this.drafting = false
      }
    },

    /**
     * Polls one run to its terminal state and returns the final read view
     * (with the produced record ids), 'failed' for a FAILED terminal status,
     * or 'unknown' when no terminal read happened within the budget. Stops
     * observing when the project switches.
     */
    async pollRunToTerminal(
      runId: string,
    ): Promise<AgentRunView | 'failed' | 'unknown'> {
      const projectId = this.projectId
      if (!projectId) return 'unknown'
      for (let attempt = 0; attempt < AGENT_RUN_MAX_POLLS; attempt += 1) {
        if (attempt > 0) {
          await new Promise((resolve) => setTimeout(resolve, AGENT_RUN_POLL_INTERVAL_MS))
          if (projectId !== this.projectId) {
            return 'unknown'
          }
        }
        try {
          const view = await getAgentRun(projectId, runId)
          if (!isTerminalRunStatus(view.status)) continue
          return view.status === 'completed' ? view : 'failed'
        } catch {
          // Transient poll failure: keep polling within budget.
        }
      }
      return 'unknown'
    },

    /**
     * Polls one question-draft run to a terminal status. Drafting has no
     * immutable-input concerns: 'completed' refreshes canonical state in the
     * caller, anything else reconciles.
     */
    async pollDraftRun(runId: string): Promise<'completed' | 'failed' | 'unknown'> {
      const outcome = await this.pollRunToTerminal(runId)
      if (outcome === 'unknown' || outcome === 'failed') return outcome
      return 'completed'
    },

    /**
     * Submits an answer through the async Agent Runtime.
     *
     * The HTTP command returns immediately with a runId (202); the model
     * workflow runs in the background worker. `submitting` therefore means
     * "a run is in flight for this node", never "an HTTP request is blocked".
     * While the run is pending only the answering node is locked; pan, zoom,
     * inspect and route navigation stay available. Completion is observed by
     * polling the run read endpoint; the canonical graph is refreshed from
     * the backend after a terminal state — never patched locally.
     */
    async submitAnswer(payload: SubmitAnswerRequest): Promise<boolean> {
      if (!this.projectId || this.submitting || this.routeCommandPending) {
        return false
      }
      const answeringNodeId = this.activeState?.activeNode?.id
        ?? this.activeState?.activeRoute?.tipNodeId
        ?? null

      this.submitting = true
      this.error = null
      this.repairableAnswerId = null
      this.resubmitAnswerPayload = null
      this.pendingAnswerNodeId = answeringNodeId
      this.answerRunId = null
      this.answerRunPhase = null
      this.answerOutcomeUnknown = false
      this.lastSubmittedAnswerPayload = { ...payload }

      let created = false
      try {
        // The backend routes an ANSWER_TIP whose node already carries a
        // persisted Answer to RESUME_ANSWER itself; the frontend never
        // guesses which one applies.
        const run = await createAgentRun(this.projectId, {
          operation: 'ANSWER_TIP',
          nodeId: answeringNodeId,
          selectedOptionId: payload.selectedOptionId ?? null,
          freeText: payload.freeText ?? null,
        })
        created = true
        this.answerRunId = run.runId
        await this.pollAnswerRun(run.runId)
        if (this.answerOutcomeUnknown) {
          // Polling ended without a terminal read (network loss beyond the
          // budget). Reconcile canonical state; never auto-resubmit.
          await this.reconcileUnknownAnswerOutcome()
          return false
        }
        return this.pendingAnswerNodeId === null
      } catch (err) {
        const safeError = toDisplayError(err)
        if (!created) {
          // The create-run request itself failed or its outcome is unknown.
          // Reconcile against canonical reads before ever allowing a second
          // mutation: only a proven absent Answer + no run may resubmit.
          const reconciled = await this.refreshWorkspace()
          let canonicalMutationCompleted = false
          if (!reconciled) {
            this.answerOutcomeUnknown = true
            this.resubmitAnswerPayload = { ...payload }
          } else {
            const answerId = this.findFinalizedAnswerForNode(answeringNodeId)
            if (answerId) {
              // An Answer was already persisted (the create request may have
              // landed even though its response was lost). Never resubmit —
              // surface repair instead.
              if (this.activeState?.activeRoute?.tipNodeId === answeringNodeId) {
                this.repairableAnswerId = answerId
                this.feedback = '回答已保存，后续生成未完成。'
              } else {
                this.pendingAnswerNodeId = null
                this.feedback = '回答已记录。'
                this.error = null
                canonicalMutationCompleted = true
              }
              this.resubmitAnswerPayload = null
            } else {
              // Canonical reads prove: no Answer, and the run was never
              // created. A one-shot resubmit is now provably safe.
              this.resubmitAnswerPayload = { ...payload }
            }
          }
          if (!canonicalMutationCompleted) this.error = safeError
          return false
        }
        // Run was created but polling ended without a terminal read (budget
        // exhausted on network loss). Do NOT resubmit: reconcile instead.
        const reconciled = await this.refreshWorkspace()
        if (!reconciled) {
          this.answerOutcomeUnknown = true
          this.error = safeError
          return false
        }
        const answerId = this.findFinalizedAnswerForNode(this.pendingAnswerNodeId)
        if (answerId && this.activeState?.activeRoute?.tipNodeId === this.pendingAnswerNodeId) {
          this.repairableAnswerId = answerId
          this.resubmitAnswerPayload = null
          this.feedback = '回答已保存，后续生成未完成。'
        } else {
          this.answerOutcomeUnknown = true
        }
        this.error = safeError
        return false
      } finally {
        this.submitting = false
      }
    },

    /**
     * Polls one answer run until a terminal status. One loop per call — the
     * same run never gets two timers because submit guards on `submitting`.
     * Network failures inside the loop keep polling within the attempt
     * budget; exhausting it surfaces an unknown outcome for reconciliation
     * instead of re-submitting anything. Stops observing when the project
     * switches.
     */
    async pollAnswerRun(runId: string): Promise<void> {
      const projectId = this.projectId
      if (!projectId) return
      for (let attempt = 0; attempt < AGENT_RUN_MAX_POLLS; attempt += 1) {
        if (attempt > 0) {
          await new Promise((resolve) => setTimeout(resolve, AGENT_RUN_POLL_INTERVAL_MS))
          if (projectId !== this.projectId) {
            // Project switched away: stop observing the old project's run.
            return
          }
        }
        try {
          const view = await getAgentRun(projectId, runId)
          this.answerRunPhase = view.phase
          if (!isTerminalRunStatus(view.status)) continue
          if (view.status === 'failed') {
            // FAILED run: the Answer may or may not be persisted. Canonical
            // reads decide between repair and resubmit affordances.
            await this.reconcileFailedAnswerRun()
            return
          }
          await this.finishSuccessfulAnswerRun(view)
          return
        } catch {
          // Transient poll failure: keep polling within budget.
        }
      }
      // Budget exhausted with no terminal read: treat as outcome unknown.
      this.answerOutcomeUnknown = true
    },

    /** COMPLETED run: refresh canonical state and clear pending affordances. */
    async finishSuccessfulAnswerRun(
      view: Awaited<ReturnType<typeof getAgentRun>>,
    ): Promise<void> {
      const answeredNodeId = view.producedNodeId ?? this.pendingAnswerNodeId
      this.feedback = '回答已记录。'
      await this.refreshWorkspace()
      this.manualModelRetry = null
      this.repairableAnswerId = null
      this.resubmitAnswerPayload = null
      this.pendingAnswerNodeId = null
      this.answerOutcomeUnknown = false
      if (answeredNodeId) {
        useInputDraftStore().clearDraft(
          this.projectId ?? '',
          answeredNodeId,
          this.activeState?.activeRoute?.id ?? null,
        )
      }
    },

    /**
     * FAILED run reconciliation: canonical reads decide whether the Answer
     * persisted (→ repair affordance, never a second submission) or nothing
     * landed (→ explicit one-shot resubmit payload).
     */
    async reconcileFailedAnswerRun(): Promise<void> {
      const reconciled = await this.refreshWorkspace()
      if (!reconciled) {
        this.answerOutcomeUnknown = true
        return
      }
      const answerId = this.findFinalizedAnswerForNode(this.pendingAnswerNodeId)
      if (answerId) {
        if (this.activeState?.activeRoute?.tipNodeId === this.pendingAnswerNodeId) {
          this.repairableAnswerId = answerId
          this.resubmitAnswerPayload = null
          this.feedback = '回答已保存，后续生成未完成。'
        } else {
          // The tip moved past the answered node: the mutation completed
          // despite the failure report. Never offer resubmit or repair.
          this.repairableAnswerId = null
          this.resubmitAnswerPayload = null
          this.pendingAnswerNodeId = null
          this.feedback = '回答已记录。'
        }
      } else {
        this.resubmitAnswerPayload = this.lastSubmittedAnswerPayload
      }
    },

    /**
     * Reconciliation after the run could not be observed to a terminal state
     * (poll network loss beyond the budget). Canonical reads decide between
     * repair (Answer persisted), completed-anyway (tip advanced), and an
     * explicit unknown-outcome affordance. Never resubmits by itself.
     */
    async reconcileUnknownAnswerOutcome(): Promise<void> {
      const reconciled = await this.refreshWorkspace()
      if (!reconciled) {
        this.answerOutcomeUnknown = true
        return
      }
      const answerId = this.findFinalizedAnswerForNode(this.pendingAnswerNodeId)
      if (answerId) {
        this.answerOutcomeUnknown = false
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
      }
      // Without a persisted Answer the run may still be executing server
      // side: keep answerOutcomeUnknown so the user reconciles instead of
      // creating a second mutation.
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

    /**
     * Repairs an existing answer checkpoint through a RESUME_ANSWER run. The
     * backend replays the original ANSWER_SUBMITTED semantics from the
     * persisted Answer, so this never creates a second Answer and the
     * frontend never re-sends its guessed copy of the user input.
     */
    async repairAnswerForActiveFlow(answerId: string): Promise<boolean> {
      if (!this.projectId || this.repairingAnswer || this.routeCommandPending) return false
      this.repairingAnswer = true
      this.error = null
      try {
        const run = await createAgentRun(this.projectId, {
          operation: 'RESUME_ANSWER',
          nodeId: this.activeState?.activeRoute?.tipNodeId ?? null,
          answerId,
        })
        this.answerRunId = run.runId
        this.answerRunPhase = run.phase
        await this.pollAnswerRun(run.runId)
        if (this.answerOutcomeUnknown) {
          this.error = toDisplayError(new ApiError(
            GENERIC_ERROR_MESSAGE, 'UNKNOWN_ERROR', 0))
          return false
        }
        this.feedback = '已重新请求后续生成。'
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
        return await this.generateSpec()
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
        const run = await createAgentRun(this.projectId, {
          operation: 'REGENERATE_NODE',
          nodeId,
          sourceRouteId: payload.sourceRouteId,
          freeText: payload.instruction ?? null,
        })
        const outcome = await this.pollRunToTerminal(run.runId)
        if (outcome !== 'unknown' && outcome !== 'failed') {
          // COMPLETED: the replacement route is now the active route; the
          // canonical refresh owns every id — never reconstructed locally.
          const replacementNodeId = outcome.producedNodeId
          await this.refreshWorkspace()
          this.feedback = '已创建换一个问题路线。'
          this.manualModelRetry = null
          const focusRouteId = this.activeState?.activeRoute?.id
          if (focusRouteId) {
            this.setFocusAfterMutation({
              routeId: focusRouteId,
              nodeId: replacementNodeId ?? null,
            })
          }
          return true
        }
        // FAILED or unknown: reconcile canonical reads through the shared
        // fail-closed reconciliation (a completed-after-poll transition shows
        // up as a brand-new active replacement route).
        const intent: Extract<ManualModelRetryIntent, { kind: 'regenerate' }> = {
          kind: 'regenerate',
          nodeId,
          payload: { ...payload },
          beforeRouteIds,
          beforeActiveRouteId,
          state: outcome === 'failed' ? 'ready' : 'needs_reconcile',
        }
        if (outcome === 'unknown') {
          this.manualModelRetry = intent
          const recovered = await this.reconcileRegenerateRetry(intent)
          if (recovered) return true
          return false
        }
        this.error = {
          code: 'AGENT_RUN_FAILED',
          message: '换一个问法的运行失败，请重试。',
        }
        this.manualModelRetry = intent
        return false
      } catch (err) {
        // Create-run request itself failed; reconcile canonical reads before
        // any retry affordance.
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
     * synthesizes a spec locally and never sets Focus here. Returns whether
     * a new snapshot landed on this route.
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
        return false
      }
      const beforeSpecIds = baselineSpecs.map((snapshot) => snapshot.id)
      try {
        const created = await createAgentRun(this.projectId, {
          operation: 'GENERATE_ARTIFACT',
        })
        const outcome = await this.pollRunToTerminal(created.runId)
        if (outcome === 'unknown' || outcome === 'failed') {
          // FAILED or outcome unknown: reconcile canonical reads through the
          // shared fail-closed reconciliation (exactly-one-new-snapshot rule).
          const intent: Extract<ManualModelRetryIntent, { kind: 'spec' }> = {
            kind: 'spec',
            routeId,
            beforeSpecIds,
            state: outcome === 'failed' ? 'ready' : 'needs_reconcile',
          }
          if (outcome === 'unknown') {
            this.manualModelRetry = intent
            const recovered = await this.reconcileSpecRetry(intent)
            if (recovered) return true
            return false
          }
          this.error = {
            code: 'AGENT_RUN_FAILED',
            message: '生成规格快照的运行失败，请重试。',
          }
          this.manualModelRetry = intent
          return false
        }
        // COMPLETED: select the produced snapshot from the canonical backend
        // list — never built up locally.
        const producedId = outcome.producedSpecSnapshotId
        const specs = await listRouteSpecs(this.projectId, routeId)
        this.specsByRoute = { ...this.specsByRoute, [routeId]: specs }
        const produced = specs.find((snapshot) => snapshot.id === producedId)
        if (!produced) {
          this.error = {
            code: 'SPEC_SNAPSHOT_NOT_FOUND',
            message: '生成的规格快照无法读取。',
          }
          return false
        }
        this.selectedSpecIdByRoute = {
          ...this.selectedSpecIdByRoute,
          [routeId]: produced.id,
        }
        this.feedback = '已生成规格快照。'
        this.manualModelRetry = null
        return true
      } catch (err) {
        // The create-run request itself failed or its outcome is unknown;
        // reconcile canonical reads before any retry affordance.
        const safeError = toDisplayError(err)
        this.error = safeError
        const disposition = classifyModelFailure(safeError.code, safeError.status)
        if (disposition === 'none') {
          this.manualModelRetry = null
          return false
        }
        const intent: Extract<ManualModelRetryIntent, { kind: 'spec' }> = {
          kind: 'spec',
          routeId,
          beforeSpecIds,
          state: disposition === 'unknown' ? 'needs_reconcile' : 'ready',
        }
        this.manualModelRetry = intent
        if (intent.state === 'needs_reconcile') {
          const recovered = await this.reconcileSpecRetry(intent)
          if (recovered) return true
        }
        return false
      } finally {
        this.generatingSpec = false
      }
    },

    // ----------------------------------------------------------------
    // Graph workspace commands (zero model calls on this path)
    // ----------------------------------------------------------------

    /** Refreshes Undo/Redo availability from the operation log. */
    async refreshUndoRedoAvailability(): Promise<void> {
      if (!this.projectId) return
      try {
        this.undoRedo = await getUndoRedoAvailability(this.projectId)
      } catch {
        // Availability is a UI affordance; failures keep the last state.
      }
    },

    /**
     * Creates the first draft idea on the empty active route. The user
     * authors content before any agent involvement — zero model calls.
     */
    async createRootIdea(): Promise<boolean> {
      if (!this.projectId || this.graphCommandPending) return false
      const activeRouteId = this.activeState?.activeRoute?.id ?? null
      if (!activeRouteId) {
        this.error = { code: 'NO_ACTIVE_ROUTE', message: '当前项目没有活动路线。' }
        return false
      }
      this.graphCommandPending = true
      try {
        await createRootDraftNode(this.projectId, activeRouteId, {
          subtype: 'NOTE',
          content: {},
        })
        this.feedback = '已创建草稿节点，直接在卡片上编辑内容。'
        await this.refreshWorkspace()
        await this.refreshUndoRedoAvailability()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.graphCommandPending = false
      }
    },

    /**
     * Continues from a node on an explicit route. The backend appends at the
     * tip or creates an explicit branch from a historical node — the UI never
     * pretends history was rewritten.
     */
    async continueFromNode(nodeId: string, routeId: string): Promise<boolean> {
      if (!this.projectId || this.graphCommandPending) return false
      this.graphCommandPending = true
      this.error = null
      try {
        const created = await appendContinuation(this.projectId, nodeId, routeId, {
          subtype: 'NOTE',
          content: {},
        })
        this.feedback = created.branched ? '已从该节点创建探索分支。' : '已在当前路线继续。'
        await this.refreshWorkspace()
        await this.refreshUndoRedoAvailability()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.graphCommandPending = false
      }
    },

    /**
     * Attaches a resource node (root of an empty route, or appended at the
     * current tip). Resources are capability context sources, not claims.
     */
    async attachResource(
      subtype: 'TEXT' | 'URL' | 'FILE' | 'IMAGE' | 'REPOSITORY' | 'API_DOCUMENTATION',
      content: Record<string, unknown>,
    ): Promise<boolean> {
      if (!this.projectId || this.graphCommandPending) return false
      const route = this.activeRoute
      if (!route) {
        this.error = { code: 'NO_ACTIVE_ROUTE', message: '当前项目没有活动路线。' }
        return false
      }
      const tipNodeId = route.tipNodeId ?? null
      this.graphCommandPending = true
      this.error = null
      try {
        await attachResourceCommand(this.projectId, route.id, tipNodeId, subtype, content)
        this.feedback = '已添加资源节点。'
        await this.refreshWorkspace()
        await this.refreshUndoRedoAvailability()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.graphCommandPending = false
      }
    },

    /** Saves an in-place edit of a still-editable user draft. */
    async reviseDraft(nodeId: string, subtype: string, text: string): Promise<boolean> {
      if (!this.projectId || this.graphCommandPending) return false
      this.graphCommandPending = true
      this.error = null
      try {
        await reviseDraftNode(this.projectId, nodeId, {
          subtype,
          content: text.trim() ? { text: text.trim() } : {},
        })
        this.feedback = '草稿已保存。'
        await this.refreshWorkspace()
        await this.refreshUndoRedoAvailability()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.graphCommandPending = false
      }
    },

    /** Confirms claim-like knowledge content (PROPOSED -> CONFIRMED). */
    async confirmKnowledge(nodeId: string): Promise<boolean> {
      if (!this.projectId || this.graphCommandPending) return false
      this.graphCommandPending = true
      this.error = null
      try {
        await setKnowledgeStatus(this.projectId, nodeId, 'CONFIRMED')
        this.feedback = '已确认该内容。'
        await this.refreshWorkspace()
        await this.refreshUndoRedoAvailability()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.graphCommandPending = false
      }
    },

    /** Undo via operation-specific compensation; never destructive. */
    async undoGraph(): Promise<boolean> {
      if (!this.projectId || this.graphCommandPending) return false
      this.graphCommandPending = true
      this.error = null
      try {
        const result = await undoGraphOperation(this.projectId)
        this.feedback = result.description
        await this.refreshWorkspace()
        await this.refreshUndoRedoAvailability()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        await this.refreshUndoRedoAvailability()
        return false
      } finally {
        this.graphCommandPending = false
      }
    },

    /** Redo only while preconditions still hold. */
    async redoGraph(): Promise<boolean> {
      if (!this.projectId || this.graphCommandPending) return false
      this.graphCommandPending = true
      this.error = null
      try {
        const result = await redoGraphOperation(this.projectId)
        this.feedback = result.description
        await this.refreshWorkspace()
        await this.refreshUndoRedoAvailability()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        await this.refreshUndoRedoAvailability()
        return false
      } finally {
        this.graphCommandPending = false
      }
    },

    /**
     * Asks AI about a node: enqueues an async query run and polls until the
     * single DECISION call finishes. The query has no graph side effects.
     */
    async askNodeAI(nodeId: string, routeId: string, question: string): Promise<boolean> {
      if (!this.projectId || !question.trim()) return false
      this.error = null
      try {
        const created = await createNodeQuery(this.projectId, nodeId, routeId, question.trim())
        this.nodeQuery = {
          nodeId,
          routeId,
          question: question.trim(),
          runId: created.runId,
          status: 'RUNNING',
          message: null,
        }
        await this.pollNodeQuery(created.runId, nodeId)
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        if (this.nodeQuery) this.nodeQuery = { ...this.nodeQuery, status: 'FAILED' }
        return false
      }
    },

    async pollNodeQuery(runId: string, nodeId: string): Promise<void> {
      if (!this.projectId) return
      const maxAttempts = 40
      for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 1500))
        try {
          const result = await getNodeQueryResult(this.projectId, nodeId, runId)
          if (result.status === 'RUNNING' || result.status === 'CREATED') continue
          this.nodeQuery = {
            nodeId,
            routeId: this.nodeQuery?.routeId ?? '',
            question: this.nodeQuery?.question ?? '',
            runId,
            status: result.status === 'FAILED' ? 'FAILED' : 'COMPLETED',
            message: result.message,
          }
          return
        } catch {
          // Transient poll failures fall through to the next attempt.
        }
      }
      this.nodeQuery = this.nodeQuery
        ? { ...this.nodeQuery, status: 'FAILED' }
        : null
      this.error = { code: 'QUERY_TIMEOUT', message: 'AI 查询超时，请稍后重试。' }
    },
  },
})
