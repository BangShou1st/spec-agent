import { defineStore } from 'pinia'
import type { DisplayError } from '@/api/displayError'
import type { AgentRunView } from '@/api/agentRuns'
import type { SpecExportVariant } from '@/api/spec'
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
import type { GraphPendingProjection, GraphRuntimeStatus } from '@/graph/graphProjection'
import type { ProjectProposalSummary } from '@/api/graphCommands'
import {
  beginProjectAction,
  loadWorkspaceAction,
  nodeRouteIdsAction,
  rebuildRunRegistryAction,
  refreshWorkspaceAction,
  restoreCanonicalRecoveryCheckpointsAction,
} from '@/stores/workspace/workspaceLoader'
import {
  answerTargetRouteTipAction,
  consumeFocusAfterMutationAction,
  draftQuestionAction,
  findFinalizedAnswerForActiveTipAction,
  findFinalizedAnswerForNodeAction,
  findForkDraftRetryRouteIdAction,
  finishSuccessfulAnswerRunAction,
  markPendingRouteFailedAction,
  pollAnswerRunAction,
  pollDraftRunAction,
  pollRunChainToTerminalAction,
  pollRunToTerminalAction,
  reconcileAnswerOutcomeAction,
  reconcileFailedAnswerRunAction,
  reconcileUnknownAnswerOutcomeAction,
  repairAnswerForActiveFlowAction,
  resubmitFailedAnswerAction,
  retryForkDraftAction,
  retryManualModelOperationAction,
  retryPendingAgentRunAction,
  setFocusAfterMutationAction,
  submitAnswerAction,
  updatePendingRouteProjectionAction,
} from '@/stores/workspace/workspaceRuns'
import {
  activateRouteAction,
  archiveRouteAction,
  deleteRouteAction,
  forkNodeAction,
  reanswerNodeAction,
  reconcileRegenerateRetryAction,
  regenerateNodeAction,
  restoreRouteAction,
} from '@/stores/workspace/routeCommands'
import {
  ensureRequirementStateAction,
  exportSpecMarkdownAction,
  generateSpecAction,
  loadRouteSpecsAction,
  reconcileSpecRetryAction,
  selectSpecForRouteAction,
} from '@/stores/workspace/specDock'
import {
  confirmKnowledgeAction,
  connectFloatingNodeAction,
  continueFromNodeAction,
  createFloatingResourceAction,
  createIdeaAction,
  createSemanticRelationAction,
  disconnectNodeAction,
  draftQuestionFromNodeAction,
  reviseDraftAction,
} from '@/stores/workspace/resources'
import { redoGraphAction, refreshUndoRedoAvailabilityAction, undoGraphAction } from '@/stores/workspace/graphUndo'
import {
  acceptConfirmableProposalAction,
  acceptNodeQueryProposalAction,
  askNodeAIAction,
  loadNodeQueryProposalsAction,
  pollNodeQueryAction,
  rejectConfirmableProposalAction,
  rejectNodeQueryProposalAction,
} from '@/stores/workspace/proposals'
import type {
  AnswerRunSessionState,
  ManualModelRetryIntent,
  MutationFocusTarget,
  PendingRouteCommand,
} from '@/stores/workspace/types'

export type {
  AnswerRunSessionState,
  AnswerRunSessionStatus,
  ManualModelRetryIntent,
  MutationFocusTarget,
  PendingRouteCommand,
} from '@/stores/workspace/types'

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
 *
 * Every action name stays here with its original signature; the body lives in
 * the per-domain module under `stores/workspace/` and is called through `this`,
 * so an action may call an action of any domain without a module cycle.
 */
export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    projectId: null as string | null,
    /**
     * Project-session counter, bumped by `beginProject`. Every async action
     * captures it (plus `projectId`) when it starts and re-validates after
     * each `await`, BEFORE writing store state — a slow request for project A
     * must never overwrite project B's canonical state, error, or flags, and
     * must not release B's loading/locks. `projectId` alone is not enough:
     * A→B→A and same-project reloads are only distinguished by the counter.
     */
    projectSessionId: 0,
    project: null as ProjectResponse | null,
    routes: [] as RouteResponse[],
    activeState: null as ActiveProjectStateResponse | null,
    requirementState: null as RequirementStateView | null,
    loading: false,
    refreshing: false,
    drafting: false,
    repairingAnswer: false,
    feedback: null as string | null,
    error: null as DisplayError | null,
    /**
     * Per-answer-run sessions (one entry per submit attempt), keyed by the
     * client request id on the session itself. ALL answer-run lifecycle
     * state (pending node, run id/phase/status, unknown outcome, repair and
     * resubmit affordances, cleanup identity) lives on the session — the
     * single-value fields below are read-only derived views. Concurrent
     * answers on different routes therefore never overwrite or clear each
     * other's state; see `AnswerRunSessionState`.
     */
    answerRunSessions: [] as AnswerRunSessionState[],
    /**
     * Reload-derived repair checkpoint: an owned Answer on the Active route
     * tip whose follow-up generation never finished. Rebuilt from canonical
     * reads on every load/refresh; run-scoped repair affordances live on the
     * sessions and take precedence in the `repairableAnswerId` getter.
     */
    canonicalRepairableAnswerId: null as string | null,
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
    /** Browser-only virtual card for a queued/failed AgentRun. */
    pendingRouteProjection: null as GraphPendingProjection | null,
    /** Latest terminal RESPOND leaf message of the last completed draft chain. */
    pendingDraftRespondMessage: null as string | null,
    /** Fork is durable even when its follow-up Draft command fails. */
    forkDraftRetryRouteId: null as string | null,

    // Spec snapshots per route (backend-derived, never authored here).
    generatingSpec: false,
    exportingSpec: false,
    loadingSpecs: false,
    specsByRoute: {} as Record<string, SpecSnapshotResponse[]>,

    // Graph workspace commands: one mutation in flight at a time.
    graphCommandPending: false,
    undoRedo: { canUndo: false, canRedo: false } as { canUndo: boolean; canRedo: boolean },
    // In-flight / finished contextual node query ("ask AI about this node").
    nodeQuery: null as {
      nodeId: string
      routeId: string | null
      question: string
      runId: string
      status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'AWAITING_APPROVAL' | 'ACCEPTED' | 'REJECTED' | 'POLICY_DENIED' | 'NOT_CONFIRMABLE'
      message: string | null
      proposalId?: string | null
      proposalStatus?: string | null
      actionFamily?: string | null
    } | null,
    /**
     * Durable pending NodeQuery proposals discovered from the backend proposal
     * list API. A PROPOSED AgentProposal must survive a page reload and a
     * newer query (which replaces `nodeQuery`): this list is reloaded on every
     * workspace load/refresh and keyed by the canonical anchor node id so the
     * Inspector on that node still exposes the pending proposal.
     */
    nodeQueryProposals: [] as ProjectProposalSummary[],
    /** 回答/决策周期的待确认提案（意图变更，需用户显式接受/拒绝）。 */
    pendingConfirmableProposals: [] as ProjectProposalSummary[],
  }),
  getters: {
    activeRoute(state): RouteResponse | null {
      return state.activeState?.activeRoute ?? null
    },
    /**
     * The session the single-value views below resolve to.
     *
     * A session that needs the user's decision (unknown outcome, repair, or
     * resubmit) wins — latest first; otherwise the most recently started
     * live session is shown. This is presentation focus ONLY: every action
     * reads and writes its own session object directly, never this getter.
     */
    focusedAnswerSession(state): AnswerRunSessionState | null {
      const live = state.answerRunSessions
      for (let i = live.length - 1; i >= 0; i -= 1) {
        const session = live[i]
        if (
          session.status === 'UNKNOWN'
          || session.status === 'REPAIRABLE'
          || session.status === 'RESUBMITTABLE'
        ) {
          return session
        }
      }
      return live.length > 0 ? live[live.length - 1] : null
    },
    /** Node whose answer run is being observed (derived, read-only). */
    pendingAnswerNodeId(): string | null {
      return this.focusedAnswerSession?.nodeId ?? null
    },
    /** In-flight answer run (async Runtime); null when no run is being polled. */
    answerRunId(): string | null {
      return this.focusedAnswerSession?.runId ?? null
    },
    /** Latest observed phase of the in-flight answer run. */
    answerRunPhase(): string | null {
      return this.focusedAnswerSession?.phase ?? null
    },
    /** Runtime status is kept separate from immutable answer/knowledge state. */
    answerRunStatus(): GraphRuntimeStatus | null {
      return this.focusedAnswerSession?.runStatus ?? null
    },
    answerOutcomeUnknown(): boolean {
      return this.focusedAnswerSession?.status === 'UNKNOWN'
    },
    /**
     * Repair affordance: a run-scoped repairable session wins; otherwise the
     * reload-derived canonical checkpoint (Active-route tip answer).
     */
    repairableAnswerId(): string | null {
      return this.focusedAnswerSession?.repairableAnswerId ?? this.canonicalRepairableAnswerId
    },
    /** Provably-safe one-shot resubmit payload of the focused session. */
    resubmitAnswerPayload(): SubmitAnswerRequest | null {
      const session = this.focusedAnswerSession
      return session !== null && session.status === 'RESUBMITTABLE'
        ? { ...session.payload }
        : null
    },
    /**
     * Submission identity of the focused session: the route the answered node
     * belonged to at submission time. Success cleanup clears the draft under
     * THIS route identity even if the runtime created or switched routes.
     */
    submittedRouteIdForCleanup(): string | null {
      return this.focusedAnswerSession?.routeId ?? null
    },
    /**
     * Routes with an answer run currently in flight.
     *
     * The lock is PER ROUTE, not global: independent routes must not block one
     * another, while the same route can never run two competing answer cycles.
     * `submitting` is the derived "any route is busy" flag kept for the UI.
     */
    answerRunsInFlight(): string[] {
      return this.answerRunSessions
        .filter((session) => session.status === 'RUNNING' && session.routeId !== null)
        .map((session) => session.routeId as string)
    },
    submitting(): boolean {
      return this.answerRunSessions.some((session) => session.status === 'RUNNING')
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
    // ---- Workspace loader: stores/workspace/workspaceLoader.ts ----
    beginProject(projectId: string): void { beginProjectAction(this, projectId) },
    async loadWorkspace(projectId: string): Promise<void> { return loadWorkspaceAction(this, projectId) },
    async refreshWorkspace(): Promise<boolean> { return refreshWorkspaceAction(this) },
    restoreCanonicalRecoveryCheckpoints(): void { restoreCanonicalRecoveryCheckpointsAction(this) },
    async rebuildRunRegistry(): Promise<void> { return rebuildRunRegistryAction(this) },

    // ---- Async runs: stores/workspace/workspaceRuns.ts ----
    updatePendingRouteProjection(view: AgentRunView): void { updatePendingRouteProjectionAction(this, view) },
    markPendingRouteFailed(message: string, terminal: boolean): void {
      markPendingRouteFailedAction(this, message, terminal)
    },
    async draftQuestion(explicitRouteId?: string): Promise<boolean> { return draftQuestionAction(this, explicitRouteId) },
    async pollRunToTerminal(
      runId: string,
      onView?: (view: AgentRunView) => void,
    ): Promise<AgentRunView | 'failed' | 'unknown'> {
      return pollRunToTerminalAction(this, runId, onView)
    },
    async pollRunChainToTerminal(
      rootRunId: string,
      onView?: (view: AgentRunView) => void,
    ): Promise<AgentRunView | 'failed' | 'unknown'> {
      return pollRunChainToTerminalAction(this, rootRunId, onView)
    },
    async pollDraftRun(runId: string): Promise<'completed' | 'failed' | 'unknown'> {
      return pollDraftRunAction(this, runId)
    },
    async submitAnswer(payload: SubmitAnswerRequest): Promise<boolean> { return submitAnswerAction(this, payload) },
    async pollAnswerRun(runId: string): Promise<void> { return pollAnswerRunAction(this, runId) },
    async finishSuccessfulAnswerRun(view: AgentRunView, session?: AnswerRunSessionState): Promise<void> {
      return finishSuccessfulAnswerRunAction(this, view, session)
    },
    async reconcileFailedAnswerRun(session?: AnswerRunSessionState): Promise<void> {
      return reconcileFailedAnswerRunAction(this, session)
    },
    async reconcileUnknownAnswerOutcome(session?: AnswerRunSessionState): Promise<void> {
      return reconcileUnknownAnswerOutcomeAction(this, session)
    },
    async reconcileAnswerOutcome(): Promise<boolean> { return reconcileAnswerOutcomeAction(this) },
    async repairAnswerForActiveFlow(
      answerId: string,
      routeId?: string | null,
      nodeId?: string | null,
    ): Promise<boolean> {
      return repairAnswerForActiveFlowAction(this, answerId, routeId, nodeId)
    },
    async resubmitFailedAnswer(): Promise<boolean> { return resubmitFailedAnswerAction(this) },
    findFinalizedAnswerForActiveTip(): string | null { return findFinalizedAnswerForActiveTipAction(this) },
    findFinalizedAnswerForNode(nodeId: string | null, routeId?: string | null): string | null {
      return findFinalizedAnswerForNodeAction(this, nodeId, routeId)
    },
    answerTargetRouteTip(): string | null { return answerTargetRouteTipAction(this) },
    findForkDraftRetryRouteId(): string | null { return findForkDraftRetryRouteIdAction(this) },
    setFocusAfterMutation(target: MutationFocusTarget | null): void { setFocusAfterMutationAction(this, target) },
    consumeFocusAfterMutation(): MutationFocusTarget | null { return consumeFocusAfterMutationAction(this) },
    async retryManualModelOperation(): Promise<boolean> { return retryManualModelOperationAction(this) },
    async reconcileRegenerateRetry(
      intent: Extract<ManualModelRetryIntent, { kind: 'regenerate' }>,
    ): Promise<boolean> {
      return reconcileRegenerateRetryAction(this, intent)
    },
    async reconcileSpecRetry(
      intent: Extract<ManualModelRetryIntent, { kind: 'spec' }>,
    ): Promise<boolean> {
      return reconcileSpecRetryAction(this, intent)
    },

    // ---- Route commands: stores/workspace/routeCommands.ts ----
    async activateRoute(routeId: string): Promise<boolean> { return activateRouteAction(this, routeId) },
    async restoreRoute(routeId: string): Promise<boolean> { return restoreRouteAction(this, routeId) },
    async archiveRoute(routeId: string): Promise<boolean> { return archiveRouteAction(this, routeId) },
    async deleteRoute(routeId: string): Promise<boolean> { return deleteRouteAction(this, routeId) },
    async forkNode(nodeId: string, sourceRouteId: string, label?: string | null): Promise<boolean> {
      return forkNodeAction(this, nodeId, sourceRouteId, label)
    },
    async retryForkDraft(): Promise<boolean> { return retryForkDraftAction(this) },
    async retryPendingAgentRun(): Promise<boolean> { return retryPendingAgentRunAction(this) },
    async reanswerNode(nodeId: string, sourceRouteId: string, label?: string | null): Promise<boolean> {
      return reanswerNodeAction(this, nodeId, sourceRouteId, label)
    },
    async regenerateNode(nodeId: string, payload: RegenerateNodeRequest): Promise<boolean> {
      return regenerateNodeAction(this, nodeId, payload)
    },

    // ---- Route-scoped reads + spec dock: stores/workspace/specDock.ts ----
    async ensureRequirementState(routeId: string): Promise<RequirementStateView | null> {
      return ensureRequirementStateAction(this, routeId)
    },
    selectSpecForRoute(routeId: string, snapshotId: string | null): void {
      selectSpecForRouteAction(this, routeId, snapshotId)
    },
    async loadRouteSpecs(routeId: string): Promise<void> { return loadRouteSpecsAction(this, routeId) },
    async generateSpec(): Promise<boolean> { return generateSpecAction(this) },
    async exportSpecMarkdown(snapshotId: string, variant: SpecExportVariant): Promise<boolean> {
      return exportSpecMarkdownAction(this, snapshotId, variant)
    },

    // ---- Graph authoring commands: stores/workspace/resources.ts ----
    async createIdea(): Promise<string | null> { return createIdeaAction(this) },
    async continueFromNode(nodeId: string, routeId: string): Promise<boolean> {
      return continueFromNodeAction(this, nodeId, routeId)
    },
    async draftQuestionFromNode(nodeId: string, readingRouteId?: string | null): Promise<boolean> {
      return draftQuestionFromNodeAction(this, nodeId, readingRouteId)
    },
    async createFloatingResource(
      subtype: 'TEXT' | 'URL' | 'FILE' | 'IMAGE' | 'REPOSITORY' | 'API_DOCUMENTATION',
      content: Record<string, unknown>,
    ): Promise<boolean> {
      return createFloatingResourceAction(this, subtype, content)
    },
    async connectFloatingNode(
      nodeId: string,
      routeId: string,
      parentNodeId: string | null,
    ): Promise<boolean> {
      return connectFloatingNodeAction(this, nodeId, routeId, parentNodeId)
    },
    async disconnectNode(nodeId: string, routeId: string): Promise<boolean> {
      return disconnectNodeAction(this, nodeId, routeId)
    },
    async reviseDraft(nodeId: string, subtype: string, text: string,
                      skillId: string | null = null): Promise<boolean> {
      return reviseDraftAction(this, nodeId, subtype, text, skillId)
    },
    async confirmKnowledge(nodeId: string): Promise<boolean> { return confirmKnowledgeAction(this, nodeId) },
    async createSemanticRelation(
      sourceNodeId: string,
      targetNodeId: string,
      relationType: 'RELATED_TO' | 'DEPENDS_ON' | 'DERIVED_FROM' | 'CONFLICTS_WITH' | 'SUPPORTS',
    ): Promise<boolean> {
      return createSemanticRelationAction(this, sourceNodeId, targetNodeId, relationType)
    },

    // ---- Undo / redo: stores/workspace/graphUndo.ts ----
    async refreshUndoRedoAvailability(): Promise<void> { return refreshUndoRedoAvailabilityAction(this) },
    async undoGraph(): Promise<boolean> { return undoGraphAction(this) },
    async redoGraph(): Promise<boolean> { return redoGraphAction(this) },

    // ---- Node query + proposals: stores/workspace/proposals.ts ----
    async askNodeAI(nodeId: string, routeId: string | null, question: string): Promise<boolean> {
      return askNodeAIAction(this, nodeId, routeId, question)
    },
    /** Resolves the route memberships of a canonical node from the graph read. */
    nodeRouteIds(nodeId: string): string[] { return nodeRouteIdsAction(this, nodeId) },
    async pollNodeQuery(query: {
      runId: string
      nodeId: string
      routeId: string | null
      question: string
    }): Promise<void> {
      return pollNodeQueryAction(this, query)
    },
    async loadNodeQueryProposals(): Promise<void> { return loadNodeQueryProposalsAction(this) },
    async acceptNodeQueryProposal(proposalId: string): Promise<boolean> {
      return acceptNodeQueryProposalAction(this, proposalId)
    },
    async acceptConfirmableProposal(proposalId: string): Promise<boolean> {
      return acceptConfirmableProposalAction(this, proposalId)
    },
    async rejectConfirmableProposal(proposalId: string): Promise<boolean> {
      return rejectConfirmableProposalAction(this, proposalId)
    },
    async rejectNodeQueryProposal(proposalId: string): Promise<boolean> {
      return rejectNodeQueryProposalAction(this, proposalId)
    },
  },
})

/**
 * Public store type. The per-domain modules under `stores/workspace/` receive
 * the store instance and declare it with this type, which they import as a
 * `import type` — so there is no runtime module cycle between the store and its
 * domain modules.
 */
export type WorkspaceStore = ReturnType<typeof useWorkspaceStore>
