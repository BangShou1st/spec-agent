// 文件名:workspaceStore.ts
// 用途:工作区应用状态 store(Pinia):canonical 服务器状态 + 运行时命令;每个 action 名保留在此,函数体拆分到 state/ 下的领域模块并经 this 委托,避免模块环。
import { defineStore } from 'pinia'
import type { DisplayError } from '@/shared/http/displayError'
import type { AgentRunView, UnresolvedFailure } from '@/features/workspace/api/agentRuns'
import type { SpecExportVariant } from '@/features/workspace/api/spec'
import type {
  ActiveProjectStateResponse,
  GraphWorkspaceView,
  ProjectResponse,
  RegenerateNodeRequest,
  RequirementStateView,
  RouteResponse,
  SpecSnapshotResponse,
  SubmitAnswerRequest,
} from '@/shared/contracts/types'
import type { GraphPendingProjection, GraphRuntimeStatus } from '@/features/workspace/graph/graphProjection'
import type { ProjectProposalSummary } from '@/features/workspace/api/graphCommands'
import {
  beginProjectAction,
  loadWorkspaceAction,
  nodeRouteIdsAction,
  rebuildRunRegistryAction,
  rebuildUnresolvedFailuresAction,
  refreshWorkspaceAction,
} from './workspaceLoader'
import {
  answerTargetRouteTipAction,
  consumeFocusAfterMutationAction,
  draftQuestionAction,
  findFinalizedAnswerForNodeAction,
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
  retryManualModelOperationAction,
  retryFailedRunAction,
  setFocusAfterMutationAction,
  submitAnswerAction,
  updatePendingRouteProjectionAction,
} from './workspaceRuns'
import {
  activateRouteAction,
  archiveRouteAction,
  deleteRouteAction,
  forkNodeAction,
  reanswerNodeAction,
  reconcileRegenerateRetryAction,
  regenerateNodeAction,
  restoreRouteAction,
} from './routeCommands'
import {
  ensureRequirementStateAction,
  exportSpecMarkdownAction,
  generateSpecAction,
  loadRouteSpecsAction,
  reconcileSpecRetryAction,
  selectSpecForRouteAction,
} from './specDock'
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
} from './resources'
import { redoGraphAction, refreshUndoRedoAvailabilityAction, undoGraphAction } from './graphUndo'
import {
  acceptConfirmableProposalAction,
  acceptNodeQueryProposalAction,
  askNodeAIAction,
  loadNodeQueryProposalsAction,
  pollNodeQueryAction,
  refreshNodeQueryResultAction,
  rejectConfirmableProposalAction,
  rejectNodeQueryProposalAction,
} from './proposals'
import type {
  AnswerRunSessionState,
  ManualModelRetryIntent,
  MutationFocusTarget,
  PendingRouteCommand,
} from './types'

export type {
  AnswerRunSessionState,
  AnswerRunSessionStatus,
  ManualModelRetryIntent,
  MutationFocusTarget,
  PendingRouteCommand,
} from './types'

/*
 * 工作区应用状态(canonical 服务器状态 + 运行时命令)。
 *
 * 前端绝不重建运行时历史:每次命令之后刷新 canonical 的后端读取 API,
 * 本 store 只镜像后端返回的内容。RequirementState 是后端派生的,客户端
 * 绝不晋升。路线生命周期绝不在本地修改——每次流转都走既有的路线命令
 * API。
 *
 * 仅浏览器的视图状态(选中、Focus、筛选、布局、侧栏)存放在
 * `graphUiStore`;本 store 绝不导入它,也绝不让 Focus 改变命令目标——
 * 起草/提交/规格生成始终以 后端 Active 路线为目标。
 *
 * 每个 action 名连同其原始签名都留在这里;函数体位于 `state/` 下的领域
 * 模块,并经 `this` 调用,因此一个 action 可以调用任何领域的 action 而
 * 不产生模块环。
 */
export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    projectId: null as string | null,
    /*
     * 项目会话计数器,由 `beginProject` 递增。每个异步 action 在开始时
     * 捕获它(加 `projectId`),并在每个 `await` 之后、写入 store 状态之前
     * 重新校验——针对项目 A 的慢请求绝不能覆盖项目 B 的 canonical 状态、
     * 错误或标志,也绝不能释放 B 的加载/锁。只有 `projectId` 不够:
     * A→B→A 与同项目刷新只能靠计数器区分。
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
    /*
     * 逐回答 run 的会话(每次提交尝试一条),以会话自身的客户端请求 id
     * 为键。所有回答 run 的生命周期状态(待处理节点、run id/阶段/状态、
     * 未知结果、修复与重提交入口、清理身份)都放在会话上——下方的单值
     * 字段只是只读的派生视图。因此不同路线上的并发回答绝不覆盖或清除
     * 彼此的状态;见 `AnswerRunSessionState`。
     */
    answerRunSessions: [] as AnswerRunSessionState[],
    /*
     * 刷新派生的修复检查点:Active 路线末端上一个后续生成未完成的已归属
     * 回答。每次加载/刷新时从 canonical 读取重建;run 范围的修复入口在
     * 会话上,并在 `repairableAnswerId` getter 中优先。
     */
    manualModelRetry: null as ManualModelRetryIntent | null,
    focusAfterMutation: null as MutationFocusTarget | null,

    // canonical 图读取(Phase 7.3A):每次刷新从后端整体替换;前端绝不
    // 在本地打补丁。
    graphView: null as GraphWorkspaceView | null,
    // 路线级需求状态缓存,以显式路线 id 为索引。
    requirementStatesByRoute: {} as Record<string, RequirementStateView>,
    loadingRequirementRouteId: null as string | null,
    // 路线级规格选择,以显式路线 id 为索引。
    selectedSpecIdByRoute: {} as Record<string, string | null>,

    // 路线命令锁定:一次只允许一条精确命令。
    routeCommandPending: false,
    pendingRouteCommand: null as PendingRouteCommand,
    /** 排队/失败 AgentRun 的仅浏览器虚拟卡片。 */
    pendingRouteProjection: null as GraphPendingProjection | null,
    /** 最近一条完成的起草链的终态 RESPOND 叶子消息。 */
    pendingDraftRespondMessage: null as string | null,
    /** 即使后续起草命令失败,fork 也已持久化。 */

    // 逐路线的规格快照(后端派生,绝不在本地撰写)。
    generatingSpec: false,
    exportingSpec: false,
    loadingSpecs: false,
    specsByRoute: {} as Record<string, SpecSnapshotResponse[]>,

    // 图工作区命令:一次只允许一个 mutation 在途。
    graphCommandPending: false,
    undoRedo: { canUndo: false, canRedo: false } as { canUndo: boolean; canRedo: boolean },
    // 在途/已完成的上下文节点查询("问 AI 这个节点")。
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
    /*
     * 从后端提案列表 API 发现的持久待确认 NodeQuery 提案。一条 PROPOSED
     * AgentProposal 必须在页面刷新与更新查询(取代 `nodeQuery`)之后存活:
     * 该列表在每次工作区加载/刷新时重新加载,并以 canonical 锚节点 id 为
     * 键,该节点的 Inspector 仍能展示这条待确认提案。
     */
    nodeQueryProposals: [] as ProjectProposalSummary[],
    /** 回答/决策周期的待确认提案（意图变更，需用户显式接受/拒绝）。 */
    pendingConfirmableProposals: [] as ProjectProposalSummary[],
  }),
  getters: {
    activeRoute(state): RouteResponse | null {
      return state.activeState?.activeRoute ?? null
    },
    /*
     * 下方单值视图所解析到的那个会话。
     *
     * 需要用户决策的会话(结果未知、修复或重提交)获胜——新的优先;
     * 否则展示最近开始的活跃会话。这只是展示焦点:每个 action 直接读写
     * 自己的会话对象,绝不读写这个 getter。
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
    /** 回答 run 正在被观察的节点(派生,只读)。 */
    pendingAnswerNodeId(): string | null {
      return this.focusedAnswerSession?.nodeId ?? null
    },
    /** 在途回答 run(异步运行时);没有 run 在轮询时为 null。 */
    answerRunId(): string | null {
      return this.focusedAnswerSession?.runId ?? null
    },
    /** 在途回答 run 最近观察到的阶段。 */
    answerRunPhase(): string | null {
      return this.focusedAnswerSession?.phase ?? null
    },
    /** 运行时状态与不可变的回答/知识状态分开保存。 */
    answerRunStatus(): GraphRuntimeStatus | null {
      return this.focusedAnswerSession?.runStatus ?? null
    },
    answerOutcomeUnknown(): boolean {
      return this.focusedAnswerSession?.status === 'UNKNOWN'
    },
    /** 焦点会话可证明安全的一次性重提交载荷。 */
    resubmitAnswerPayload(): SubmitAnswerRequest | null {
      const session = this.focusedAnswerSession
      return session !== null && session.status === 'RESUBMITTABLE'
        ? { ...session.payload }
        : null
    },
    /*
     * 焦点会话的提交身份:被回答节点在提交时所属的路线。成功清理按这条
     * 路线身份清除草稿,即使运行时创建或切换了路线。
     */
    submittedRouteIdForCleanup(): string | null {
      return this.focusedAnswerSession?.routeId ?? null
    },
    /*
     * 回答 run 当前在途的路线。
     *
     * 锁是逐路线的,不是全局:独立路线绝不能互相阻塞,而同一条路线绝不
     * 能同时跑两个竞争的回答周期。`submitting` 是为 UI 保留的派生
     * "任意路线忙碌"标志。
     */
    answerRunsInFlight(): string[] {
      return this.answerRunSessions
        .filter((session) => session.status === 'RUNNING' && session.routeId !== null)
        .map((session) => session.routeId as string)
    },
    submitting(): boolean {
      return this.answerRunSessions.some((session) => session.status === 'RUNNING')
    },
    /** 为一条显式路线解析选中的快照。 */
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
    // ---- Workspace loader: state/workspaceLoader.ts ----
    beginProject(projectId: string): void { beginProjectAction(this, projectId) },
    async loadWorkspace(projectId: string): Promise<void> { return loadWorkspaceAction(this, projectId) },
    async refreshWorkspace(): Promise<boolean> { return refreshWorkspaceAction(this) },
    async rebuildRunRegistry(): Promise<void> { return rebuildRunRegistryAction(this) },
    async rebuildUnresolvedFailures(): Promise<void> { return rebuildUnresolvedFailuresAction(this) },

    // ---- Async runs: state/workspaceRuns.ts ----
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
    findFinalizedAnswerForNode(nodeId: string | null, routeId?: string | null): string | null {
      return findFinalizedAnswerForNodeAction(this, nodeId, routeId)
    },
    answerTargetRouteTip(): string | null { return answerTargetRouteTipAction(this) },
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

    // ---- Route commands: state/routeCommands.ts ----
    async activateRoute(routeId: string): Promise<boolean> { return activateRouteAction(this, routeId) },
    async restoreRoute(routeId: string): Promise<boolean> { return restoreRouteAction(this, routeId) },
    async archiveRoute(routeId: string): Promise<boolean> { return archiveRouteAction(this, routeId) },
    async deleteRoute(routeId: string): Promise<boolean> { return deleteRouteAction(this, routeId) },
    async forkNode(nodeId: string, sourceRouteId: string, label?: string | null): Promise<boolean> {
      return forkNodeAction(this, nodeId, sourceRouteId, label)
    },
    async retryFailedRun(failure: UnresolvedFailure): Promise<boolean> { return retryFailedRunAction(this, failure) },
    async reanswerNode(nodeId: string, sourceRouteId: string, label?: string | null): Promise<boolean> {
      return reanswerNodeAction(this, nodeId, sourceRouteId, label)
    },
    async regenerateNode(nodeId: string, payload: RegenerateNodeRequest): Promise<boolean> {
      return regenerateNodeAction(this, nodeId, payload)
    },

    // ---- Route-scoped reads + spec dock: state/specDock.ts ----
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

    // ---- Graph authoring commands: state/resources.ts ----
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

    // ---- Undo / redo: state/graphUndo.ts ----
    async refreshUndoRedoAvailability(): Promise<void> { return refreshUndoRedoAvailabilityAction(this) },
    async undoGraph(): Promise<boolean> { return undoGraphAction(this) },
    async redoGraph(): Promise<boolean> { return redoGraphAction(this) },

    // ---- Node query + proposals: state/proposals.ts ----
    async askNodeAI(nodeId: string, routeId: string | null, question: string): Promise<boolean> {
      return askNodeAIAction(this, nodeId, routeId, question)
    },
    /** 从图读取解析 canonical 节点的路线归属。 */
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
    /** 节点查询重试成功后刷新检查器展示的结果(6-5)。 */
    async refreshNodeQueryResult(
      nodeId: string,
      runId: string,
      fallback: { routeId: string | null; question: string },
    ): Promise<void> {
      return refreshNodeQueryResultAction(this, nodeId, runId, fallback)
    },
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

/*
 * store 的公开类型。`state/` 下的领域模块接收 store 实例并用该类型声明,
 * 以 `import type` 导入——因此 store 与其领域模块之间没有运行时模块环。
 */
export type WorkspaceStore = ReturnType<typeof useWorkspaceStore>
