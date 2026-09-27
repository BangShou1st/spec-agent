// 文件名:workspaceRuns.ts
// 用途:工作区 run 领域:异步 Agent 运行时生命周期(起草/作答/轮询 run 链到终态叶子/fail-closed 结果对账/手动重试),以及回答恢复读取与 mutation 后的聚焦交接。
/*
 * 工作区 run 领域:异步 Agent 运行时的生命周期。
 *
 * 起草、作答、把 run 链轮询到终态叶子、对 fail-closed 的结果状态做对账,
 * 以及失败/未知 run 之后 UI 呈现的手动重试入口。还负责回答恢复读取,以及
 * 由它们喂养的 mutation 后聚焦交接。
 *
 * 每个函数都是从 `workspaceStore.ts` 原样搬出的 action 主体,只是把
 * `this` 换成了作为第一个参数传入的 store 实例。store 保留原 action 名
 * 并委托到这里,调用方零改动。
 */
import { reactive } from 'vue'
import { sleep } from '@/shared/lib/timing'
import {
  AGENT_RUN_MAX_POLLS,
  AGENT_RUN_POLL_INTERVAL_MS,
  createAgentRun,
  getAgentRun,
  isTerminalRunStatus,
  retryAgentRun,
} from '@/features/workspace/api/agentRuns'
import type { AgentRunView, UnresolvedFailure } from '@/features/workspace/api/agentRuns'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/shared/http/client'
import { toDisplayError } from '@/shared/http/displayError'
import { classifyModelFailure } from '@/shared/http/errorCopy'
import { useInputDraftStore } from './inputDraftStore'
import { useRunRegistryStore } from './runRegistryStore'
import type { GraphPendingProjection } from '@/features/workspace/graph/graphProjection'
import type { SubmitAnswerRequest } from '@/shared/contracts/types'
import type { AnswerRunSlice } from './slices'
import type {
  AnswerRunSessionState,
  ManualModelRetryIntent,
  MutationFocusTarget,
} from './types'
import { withAnswerableNodeHint } from './shared'

// ---- 回答 run 会话辅助 ------------------------------------------------------
//
// 每次回答尝试都在 store 上拥有一个 `AnswerRunSessionState` 条目。下方
// 所有生命周期写入都走会话对象——绝不走 store 的派生单值 getter——因此
// 不同路线上的两个并发回答完全隔离。

/** 此会话仍被本 store 实例追踪时为 true。项目切换(beginProject)会丢弃
 * 全部会话;被脱离的会话绝不能把反馈/错误写进新项目。 */
function isSessionTracked(store: AnswerRunSlice, session: AnswerRunSessionState): boolean {
  return store.answerRunSessions.includes(session)
}

function removeAnswerSession(store: AnswerRunSlice, session: AnswerRunSessionState): void {
  const index = store.answerRunSessions.indexOf(session)
  if (index >= 0) store.answerRunSessions.splice(index, 1)
}

/*
 * 一次成功的恢复只取代与确切回答目标匹配的过期会话。其它回答/路线保持
 * 可见,仍在途的另一条 run 绝不会被本 run 的终态观察取消。
 */
function removeCompletedAnswerRecoverySessions(
  store: AnswerRunSlice,
  target: AnswerRunSessionState,
  answerId: string | null,
): void {
  const targetRouteId = target.routeId ?? null
  for (let index = store.answerRunSessions.length - 1; index >= 0; index -= 1) {
    const candidate = store.answerRunSessions[index]
    if (candidate === target) {
      store.answerRunSessions.splice(index, 1)
      continue
    }
    if (!answerId
      || candidate.status === 'RUNNING'
      || candidate.projectId !== target.projectId
      || (candidate.routeId ?? null) !== targetRouteId
      || candidate.nodeId !== target.nodeId
      || candidate.repairableAnswerId !== answerId) {
      continue
    }
    store.answerRunSessions.splice(index, 1)
  }
}

/** 从 canonical 图读取某条显式路线的实时末端。绝不是 Active 指针:
 * 多路线工作下它可能指向另一条路线。 */
function routeTipOf(store: AnswerRunSlice, routeId: string | null): string | null {
  if (!routeId) return null
  return store.graphView?.routes.find((route) => route.id === routeId)?.tipNodeId ?? null
}

function findSessionByRunId(store: AnswerRunSlice, runId: string): AnswerRunSessionState | null {
  return store.answerRunSessions.find((session) => session.runId === runId) ?? null
}

/*
 * canonical 读取:本会话提交到的那条路线是否已为被回答节点落定了一条
 * 回答?多路线工作下该路线不是 Active 路线,因此搜索 Active 路线的回答
 * 会错误报告"什么都没落地",并对一条已存在的回答提供重提交。
 */
function findFinalizedAnswerForSession(
  store: AnswerRunSlice,
  session: AnswerRunSessionState,
): string | null {
  const routeId = session.routeId ?? store.activeState?.activeRoute?.id ?? null
  if (!routeId || !session.nodeId) return null
  return store.graphView?.answers.find((answer) =>
    answer.routeId === routeId
    && answer.nodeId === session.nodeId
    && answer.inherited === false
    && answer.ownerRouteId === routeId,
  )?.id ?? null
}

function historicalRecoveryAnswerId(
  store: AnswerRunSlice,
  session: AnswerRunSessionState,
): string | null {
  return findFinalizedAnswerForSession(store, session)
    ?? (session.historicalRecovery ? session.repairableAnswerId : null)
}

function retainHistoricalRecovery(
  store: AnswerRunSlice,
  session: AnswerRunSessionState,
  answerId: string | null,
): void {
  if (answerId) session.repairableAnswerId = answerId
  session.status = 'REPAIRABLE'
  store.feedback = '历史回答恢复未完成，请重试恢复'
}

export function updatePendingRouteProjectionAction(store: AnswerRunSlice, view: AgentRunView): void {
  const current = store.pendingRouteProjection
  if (!current || current.runId !== view.runId) return
  const routeId = typeof view.routeId === 'string' ? view.routeId.trim() : ''
  if (!routeId) {
    store.markPendingRouteFailed('运行结果缺少路线标识，已停止显示临时卡片', true)
    return
  }
  const status: GraphPendingProjection['status'] = view.status === 'failed'
    ? 'FAILED'
    : view.status === 'completed'
      ? 'SUCCEEDED'
      : view.status === 'created'
        ? 'PENDING'
        : 'RUNNING'
  store.pendingRouteProjection = {
    ...current,
    routeId,
    status,
    phase: view.phase || current.phase,
  }
}

export function markPendingRouteFailedAction(
  store: AnswerRunSlice,
  message: string,
  terminal: boolean,
): void {
  const current = store.pendingRouteProjection
  if (!current) return
  store.pendingRouteProjection = {
    ...current,
    status: terminal ? 'FAILED' : current.status,
    phase: terminal ? 'FAILED' : current.phase,
    message,
  }
}

/*
 * 经异步 Agent 运行时起草下一个问题。仅限显式用户动作;新项目在触发
 * 之前不会排入任何 run。
 *
 * 显式路线模式：从路线菜单 / 路线末端节点发起时传入 routeId，整个 run
 * 绑定该路线（与 ANSWER_TIP 的显式模式一致，多路线可各自独立起草）；
 * 缺省时保持原有 Active 路线语义不变。
 */
export async function draftQuestionAction(
  store: AnswerRunSlice,
  explicitRouteId?: string,
): Promise<boolean> {
  if (!store.projectId || store.drafting || store.routeCommandPending) {
    return false
  }
  // 起草身份:用户动作开始时捕获的项目(及其会话)。每个 await 之后、
  // 任何 store 写入之前都重新校验该身份,因此一次慢的起草绝不能把它的
  // 错误/反馈泄漏到另一个项目会话,也绝不能清掉别的会话的投影。
  const projectId = store.projectId
  const projectSessionId = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === projectSessionId && store.projectId === projectId
  store.drafting = true
  store.error = null
  store.pendingDraftRespondMessage = null
  const activeRouteId = store.activeState?.activeRoute?.id
    ?? store.project?.activeRouteId
    ?? null
  const beforeRouteId = explicitRouteId ?? activeRouteId
  if (!beforeRouteId) {
    store.error = {
      code: 'ACTIVE_ROUTE_REQUIRED',
      message: '当前没有可用路线，无法起草问题',
    }
    store.manualModelRetry = null
    store.pendingRouteProjection = null
    store.drafting = false
    return false
  }
  // Tip 读自路线视图（显式路线也成立）；视图缺失时才回落 Active 指针。
  const routeInView = store.graphView?.routes.find(
    (route) => route.id === beforeRouteId,
  ) ?? null
  const beforeTipNodeId = routeInView
    ? routeInView.tipNodeId
    : beforeRouteId === activeRouteId
      ? store.activeState?.activeRoute?.tipNodeId ?? null
      : null
  try {
    const run = await createAgentRun(projectId, {
      operation: 'DRAFT_QUESTION',
      sourceRouteId: explicitRouteId && explicitRouteId !== activeRouteId
        ? explicitRouteId
        : null,
    })
    if (!isCurrent()) return false
    store.pendingRouteProjection = {
      routeId: beforeRouteId,
      sourceNodeId: beforeTipNodeId,
      runId: run.runId,
      status: run.phase === 'CREATED' ? 'PENDING' : 'RUNNING',
      phase: run.phase || 'CREATED',
      message: null,
    }
    useRunRegistryStore().register({
      runId: run.runId,
      operation: 'DRAFT_QUESTION',
      routeId: beforeRouteId,
      sourceNodeId: beforeTipNodeId,
    })
    const outcome = await store.pollDraftRun(run.runId)
    if (!isCurrent()) return false
    if (outcome === 'completed') {
      // 终态 RESPOND 叶子携带用户可见消息;图 mutation 叶子保留既有的
      // 起草确认文案。
      store.feedback = store.pendingDraftRespondMessage ?? '问题已起草'
      const refreshed = await store.refreshWorkspace()
      if (!isCurrent()) return false
      if (refreshed) store.pendingRouteProjection = null
      store.manualModelRetry = null
      return true
    }
    // FAILED 或结果未知:对照 canonical 读取对账,再以起草前的图状态为键
    // 呈现重试入口。
    // 目标路线的 tip 是否前进是"草稿已落地"的判据；显式路线同样成立。
    const reconciled = await store.refreshWorkspace()
    if (!isCurrent()) return false
    const afterRouteInView = reconciled
      ? store.graphView?.routes.find((route) => route.id === beforeRouteId) ?? null
      : null
    const afterActiveRouteId = store.activeState?.activeRoute?.id ?? null
    const afterTipNodeId = afterRouteInView
      ? afterRouteInView.tipNodeId
      : store.activeState?.activeRoute?.tipNodeId ?? null
    if (
      reconciled
        && (afterRouteInView
          ? afterTipNodeId !== beforeTipNodeId
          : (afterActiveRouteId !== beforeRouteId || afterTipNodeId !== beforeTipNodeId))
    ) {
      // 草稿其实已经落地(例如 run 在最后一次轮询之后才完成);绝不提供
      // 会造成重复起草的重试。
      store.manualModelRetry = null
      store.pendingRouteProjection = null
      store.error = null
      store.feedback = '问题已起草'
      return true
    }
    store.error = {
      code: outcome === 'failed' ? 'AGENT_RUN_FAILED' : 'AGENT_RUN_OUTCOME_UNKNOWN',
      message: outcome === 'failed'
        ? '起草问题的运行失败，请重试'
        : '起草结果未知，已按最新状态核对。请重试',
    }
    store.manualModelRetry = {
      kind: 'draft',
      beforeRouteId,
      beforeTipNodeId,
      state: outcome === 'failed' ? 'ready' : 'needs_reconcile',
    } as ManualModelRetryIntent
    store.markPendingRouteFailed('起草问题的运行失败，请重试', outcome === 'failed')
    return false
  } catch (err) {
    // 创建 run 的请求本身失败;run 可能存在也可能不存在。允许重试之前
    // 先对账 canonical 状态。
    if (!isCurrent()) return false
    const safeError = toDisplayError(err)
    store.error = safeError
    const reconciled = await store.refreshWorkspace()
    if (!isCurrent()) return false
    const afterRouteInView = reconciled
      ? store.graphView?.routes.find((route) => route.id === beforeRouteId) ?? null
      : null
    const afterActiveRouteId = store.activeState?.activeRoute?.id ?? null
    const afterTipNodeId = afterRouteInView
      ? afterRouteInView.tipNodeId
      : store.activeState?.activeRoute?.tipNodeId ?? null
    if (
      reconciled
        && (afterRouteInView
          ? afterTipNodeId !== beforeTipNodeId
          : (afterActiveRouteId !== beforeRouteId || afterTipNodeId !== beforeTipNodeId))
    ) {
      store.manualModelRetry = null
      store.pendingRouteProjection = null
      store.error = null
      store.feedback = '问题已起草'
      return true
    }
    const disposition = classifyModelFailure(safeError.code, safeError.status)
    store.manualModelRetry = disposition === 'none' ? null : {
      kind: 'draft',
      beforeRouteId,
      beforeTipNodeId,
      state: disposition === 'unknown' ? 'needs_reconcile' : 'ready',
    } as ManualModelRetryIntent
    if (disposition !== 'none') {
      store.markPendingRouteFailed(safeError.message, disposition === 'retryable')
    }
    return false
  } finally {
    // 只有持有会话的一方释放起草标志:过期起草的清理绝不能释放新会话的
    // 标志(beginProject 已经在那里重置过)。
    if (isCurrent()) {
      store.drafting = false
    }
  }
}

/*
 * 把一条 run 轮询到终态,返回最终读取视图(含产出的记录 id)、FAILED
 * 终态对应的 'failed',或预算内没有读到终态时的 'unknown'。项目切换时
 * 停止观察。
 */
export async function pollRunToTerminalAction(
  store: AnswerRunSlice,
  runId: string,
  onView?: (view: AgentRunView) => void,
): Promise<AgentRunView | 'failed' | 'unknown'> {
  const projectId = store.projectId
  const projectSessionId = store.projectSessionId
  if (!projectId) return 'unknown'
  const isCurrent = (): boolean =>
    store.projectSessionId === projectSessionId && store.projectId === projectId
  for (let attempt = 0; attempt < AGENT_RUN_MAX_POLLS; attempt += 1) {
    if (attempt > 0) {
      await sleep(AGENT_RUN_POLL_INTERVAL_MS)
      if (!isCurrent()) {
        return 'unknown'
      }
    }
    try {
      const view = await getAgentRun(projectId, runId)
      // await 后的身份检查:读取可能在用户切换项目之后才 resolve——
      // 那时绝不能把过期的 run 喂进注册表。
      if (!isCurrent()) return 'unknown'
      useRunRegistryStore().feed(view)
      onView?.(view)
      if (!isTerminalRunStatus(view.status)) continue
      return view.status === 'completed' ? view : 'failed'
    } catch {
      // 瞬时轮询失败:在预算内继续轮询。
    }
  }
  return 'unknown'
}

/*
 * 追踪一条自主 run 链到它的终态叶子。带 childRunId 的 COMPLETED run 继续
 * 追子 run;没有子 run 但有待续检查的 COMPLETED run 保持轮询同一条 run,
 * 直到调度器/恢复流程创建子 run。轮询预算整条链共享,长链不可能永远
 * 轮询。返回终态叶子视图、FAILED 叶子对应的 'failed',或预算耗尽/项目
 * 切换时的 'unknown'。
 */
export async function pollRunChainToTerminalAction(
  store: AnswerRunSlice,
  rootRunId: string,
  onView?: (view: AgentRunView) => void,
): Promise<AgentRunView | 'failed' | 'unknown'> {
  const projectId = store.projectId
  const projectSessionId = store.projectSessionId
  if (!projectId) return 'unknown'
  const isCurrent = (): boolean =>
    store.projectSessionId === projectSessionId && store.projectId === projectId
  let currentRunId = rootRunId
  for (let attempt = 0; attempt < AGENT_RUN_MAX_POLLS; attempt += 1) {
    if (attempt > 0) {
      await sleep(AGENT_RUN_POLL_INTERVAL_MS)
      if (!isCurrent()) {
        return 'unknown'
      }
    }
    try {
      const view = await getAgentRun(projectId, currentRunId)
      // await 后的身份检查:读取可能在用户切换项目之后才 resolve——
      // 那时绝不能继续追踪过期的链。
      if (!isCurrent()) return 'unknown'
      useRunRegistryStore().feed(view)
      onView?.(view)
      if (!isTerminalRunStatus(view.status)) continue
      if (view.status === 'failed') {
        // run 到达失败终态:立即对账服务端未解决失败清单,让节点/占位卡
        // 的恢复入口立刻出现,而不是等到下一次工作区刷新。
        void store.rebuildUnresolvedFailures()
        return 'failed'
      }
      if (view.childRunId) {
        currentRunId = view.childRunId
        continue
      }
      if (view.continuationPending) continue
      return view
    } catch {
      // 瞬时轮询失败:在预算内继续轮询。
    }
  }
  return 'unknown'
}

/*
 * 把一条问题起草 run 链轮询到终态叶子。起草没有不可变输入的顾虑:
 * 'completed' 时由调用方刷新 canonical 状态,其余情况做对账。
 */
export async function pollDraftRunAction(
  store: AnswerRunSlice,
  runId: string,
): Promise<'completed' | 'failed' | 'unknown'> {
  const projectSessionId = store.projectSessionId
  const outcome = await store.pollRunChainToTerminal(
    runId,
    (view) => store.updatePendingRouteProjection(view),
  )
  // await 后的身份检查:过期轮询绝不能把它的 respond 消息写进另一个
  // 项目会话。
  if (store.projectSessionId !== projectSessionId) return 'unknown'
  if (outcome === 'unknown' || outcome === 'failed') return outcome
  if (outcome.respondMessage) {
    store.pendingDraftRespondMessage = outcome.respondMessage
  }
  return 'completed'
}

/*
 * 经异步 Agent 运行时提交回答。
 *
 * HTTP 命令立即返回 runId(202);模型工作流在后台 worker 中运行。因此
 * `submitting` 的含义是"该节点有一个 run 在途",绝不是"一个 HTTP 请求
 * 被阻塞"。run 待处理期间只有被回答的节点被锁定;平移、缩放、检查与
 * 路线导航保持可用。完成通过轮询 run 读取端点观察;终态之后从后端刷新
 * canonical 图——绝不在本地打补丁。
 */
export async function submitAnswerAction(
  store: AnswerRunSlice,
  payload: SubmitAnswerRequest,
): Promise<boolean> {
  if (!store.projectId || store.routeCommandPending) {
    return false
  }
  // 整次尝试都绑定到在此捕获的项目身份。
  const projectId = store.projectId
  // 目标解析:显式目标(用户正在阅读的路线的末端)优先;否则 Active
  // 路线的当前节点——原有行为,未变。
  const activeRouteId = store.activeState?.activeRoute?.id ?? null
  const answeringNodeId = payload.nodeId
    ?? store.activeState?.activeNode?.id
    ?? store.activeState?.activeRoute?.tipNodeId
    ?? null
  const submittedRouteId = payload.routeId ?? activeRouteId
  // 每条路线同时至多一个在途回答 run:另一条路线的链绝不能阻塞本条
  // (这正是独立路线的全部意义),而同一条路线绝不能同时跑两个竞争的
  // 回答周期。
  if (!answeringNodeId || (submittedRouteId !== null
    && store.answerRunsInFlight.includes(submittedRouteId))) {
    return false
  }
  // 提交身份在用户动作开始时固定:此刻被回答的节点及其路线。成功清理
  // 使用的正是这些——绝不是产出的 id 或刷新后的路线指针。
  const submittedNodeId = answeringNodeId
  // 每次用户动作尝试一个稳定的幂等身份:结果未知的重试(创建请求丢失、
  // 响应丢失)复用同一个键,后端因此返回已创建的那条 run。
  const clientRequestId = crypto.randomUUID()

  // 对同一目标(同项目 + 路线 + 节点)的新尝试会取代上一次尝试的恢复
  // 入口——那个旧会话正是用户正在重试的对象。其它目标(其它路线/节点)
  // 的会话绝不被触碰:一条 run 绝不能清除或覆盖另一条 run 的错误或
  // 恢复状态。
  store.answerRunSessions = store.answerRunSessions.filter((existing) => {
    const sameTarget = existing.projectId === projectId
      && existing.nodeId === submittedNodeId
      && (existing.routeId ?? null) === (submittedRouteId ?? null)
    return !(sameTarget && existing.status !== 'RUNNING')
  })

  // 用 `reactive()` 包装,让 store 的派生 getter(answerRunId、submitting、
  // 恢复入口)能对会话的就地生命周期更新作出反应——推入响应式数组的裸
  // 对象会静默变异而不触发更新。
  const session = reactive<AnswerRunSessionState>({
    clientRequestId,
    projectId,
    routeId: submittedRouteId,
    nodeId: submittedNodeId,
    payload: { ...payload },
    runId: null,
    phase: null,
    runStatus: 'PENDING',
    status: 'RUNNING',
    repairableAnswerId: null,
  })
  store.answerRunSessions.push(session)

  store.error = null
  let created = false
  try {
    // 节点已带持久化回答的 ANSWER_TIP 由后端自行路由到 RESUME_ANSWER;
    // 前端绝不猜测适用哪一个。
    //
    // EXPLICIT 路线模式只在目标不是 Active 路线时启用:Active 路径的
    // fail-closed 保证(Active 指针移动过的 run 仍必须失败)原样保留。
    const run = await createAgentRun(projectId, {
      operation: 'ANSWER_TIP',
      nodeId: submittedNodeId,
      sourceRouteId: submittedRouteId !== null && submittedRouteId !== activeRouteId
        ? submittedRouteId
        : null,
      selectedOptionId: payload.selectedOptionId ?? null,
      selectedOptionIds: payload.selectedOptionIds ?? null,
      freeText: payload.freeText ?? null,
      idempotencyKey: clientRequestId,
    })
    if (!isSessionTracked(store, session)) return false
    created = true
    session.runId = run.runId
    session.runStatus = 'RUNNING'
    session.phase = run.phase ?? null
    useRunRegistryStore().register({
      runId: run.runId,
      operation: 'ANSWER_TIP',
      routeId: submittedRouteId,
      sourceNodeId: submittedNodeId,
    })
    await store.pollAnswerRun(run.runId)
    if (isSessionTracked(store, session) && session.status === 'UNKNOWN') {
      // 轮询结束却没有读到终态(预算之外的网络丢失)。对账 canonical
      // 状态;绝不自动重提交。
      await reconcileUnknownAnswerOutcomeAction(store, session)
    }
    // 轮询已就地把会话落定:完全处理的成功时它已消失,否则带着只属于
    // 这次尝试的恢复状态(REPAIRABLE / RESUBMITTABLE / UNKNOWN)。
    return !isSessionTracked(store, session)
  } catch (err) {
    if (!isSessionTracked(store, session)) return false
    const safeError = toDisplayError(err)
    if (!created) {
      // 创建 run 的请求本身失败或结果未知。在允许第二次 mutation 之前
      // 先对账 canonical 读取:只有被证明无回答且无 run 时才可重提交。
      const reconciled = await store.refreshWorkspace()
      if (!isSessionTracked(store, session)) return false
      if (!reconciled) {
        session.status = 'UNKNOWN'
        store.error = safeError
        return false
      }
      const answerId = findFinalizedAnswerForSession(store, session)
      if (answerId) {
        // 回答已经持久化(创建请求可能已落地,只是响应丢了)。绝不重提交
        // ——改用修复入口。
        if (routeTipOf(store, session.routeId) === session.nodeId) {
          session.status = 'REPAIRABLE'
          session.repairableAnswerId = answerId
          store.feedback = '回答已保存，后续生成未完成'
          store.error = withAnswerableNodeHint(
            safeError,
            store.activeState?.activeNode?.question ?? null,
          )
        } else {
          // 末端已越过被回答节点:mutation 实际已完成。
          removeAnswerSession(store, session)
          store.feedback = '回答已记录'
          store.error = null
        }
      } else {
        // canonical 读取证明:没有回答,run 也从未创建。一次性重提交现在
        // 可证明是安全的。
        session.status = 'RESUBMITTABLE'
        store.error = withAnswerableNodeHint(
          safeError,
          store.activeState?.activeNode?.question ?? null,
        )
      }
      return false
    }
    // run 已创建,但轮询结束却没有读到终态(预算耗尽于网络丢失)。
    // 不要重提交:改为对账。
    const reconciled = await store.refreshWorkspace()
    if (!isSessionTracked(store, session)) return false
    if (!reconciled) {
      session.status = 'UNKNOWN'
      store.error = safeError
      return false
    }
    const answerId = findFinalizedAnswerForSession(store, session)
    if (answerId && routeTipOf(store, session.routeId) === session.nodeId) {
      session.status = 'REPAIRABLE'
      session.repairableAnswerId = answerId
      store.feedback = '回答已保存，后续生成未完成'
    } else {
      session.status = 'UNKNOWN'
    }
    store.error = safeError
    return false
  }
}

/*
 * 把一条回答 run 链轮询到终态叶子。被轮询的会话按 run id 解析,所有观察
 * 都写在该会话上——并发回答 run 各自拥有自己的轮询循环,绝不覆盖彼此的
 * 阶段/状态。会话一旦不再被追踪(项目切换 / 工作区刷新)循环就停止观察,
 * sleep 之后与每次 await 的读取之后都检查。只有终态叶子才能判定成功:
 * 带子 run 的中间 COMPLETED 父节点绝不能提前结束。
 */
export async function pollAnswerRunAction(store: AnswerRunSlice, runId: string): Promise<void> {
  const session = findSessionByRunId(store, runId)
  if (!session) return
  const projectId = session.projectId
  let currentRunId = runId
  for (let attempt = 0; attempt < AGENT_RUN_MAX_POLLS; attempt += 1) {
    if (attempt > 0) {
      await sleep(AGENT_RUN_POLL_INTERVAL_MS)
      if (!isSessionTracked(store, session)) {
        // 项目已切换或工作区已刷新:停止观察。
        return
      }
    }
    try {
      const view = await getAgentRun(projectId, currentRunId)
      if (!isSessionTracked(store, session)) return
      useRunRegistryStore().feed(view)
      session.phase = view.phase
      session.runStatus = view.status === 'failed'
        ? 'FAILED'
        : view.status === 'completed'
          ? 'SUCCEEDED'
          : view.status === 'created'
            ? 'PENDING'
            : 'RUNNING'
      if (!isTerminalRunStatus(view.status)) continue
      if (view.status === 'failed') {
        // FAILED run:回答可能已持久化也可能没有。由 canonical 读取在修复
        // 与重提交入口之间裁决。
        await store.reconcileFailedAnswerRun(session)
        return
      }
      if (view.childRunId) {
        currentRunId = view.childRunId
        continue
      }
      if (view.continuationPending) continue
      if (session.historicalRecovery && !view.producedPatchId) {
        // 历史检查点只有在终态 run 报告了它要恢复的 Patch 时才算成功。
        // 仅一个 completed 状态绝不能抹掉重试入口。
        await store.reconcileFailedAnswerRun(session)
        return
      }
      await store.finishSuccessfulAnswerRun(view, session)
      return
    } catch {
      // 瞬时轮询失败:在预算内继续轮询。
      if (!isSessionTracked(store, session)) return
    }
  }
  // 预算耗尽且没有读到终态:这次尝试的结果未知;调用方对照 canonical
  // 读取做对账。
  if (isSessionTracked(store, session)) {
    session.status = 'UNKNOWN'
  }
}

/*
 * 一次回答尝试的终态链叶子:刷新 canonical 状态并只落定这个会话。清理
 * 身份是用户动作开始时捕获的"提交时"回答目标——绝不是 producedNodeId
 * (它指向运行时生成的下一个节点),也绝不是刷新后重新读取的路线 id。
 * 其它会话(其它路线的 run)不受影响。
 */
export async function finishSuccessfulAnswerRunAction(
  store: AnswerRunSlice,
  view: Awaited<ReturnType<typeof getAgentRun>>,
  session?: AnswerRunSessionState,
): Promise<void> {
  const target = session ?? findSessionByRunId(store, view.runId)
  if (!target) return
  const leafMessage = view.respondMessage ?? null
  // 第一次写入之前的会话守卫:项目若已切换,这个终态叶子绝不能把反馈写
  // 进新纪元。
  if (!isSessionTracked(store, target)) return
  if (target.historicalRecovery && !view.producedPatchId) {
    retainHistoricalRecovery(store, target, historicalRecoveryAnswerId(store, target))
    return
  }
  store.feedback = leafMessage ?? '回答已记录'
  await store.refreshWorkspace()
  if (!isSessionTracked(store, target)) return
  // 起草重试意图只有属于本路线时才清除——另一条路线上的并发起草重试
  // 与本 run 无关。
  const intent = store.manualModelRetry
  if (intent) {
    const intentRouteId = intent.kind === 'draft'
      ? intent.beforeRouteId
      : intent.kind === 'spec'
        ? intent.routeId
        : intent.beforeActiveRouteId
    if (intentRouteId === null || intentRouteId === target.routeId) {
      store.manualModelRetry = null
    }
  }
  removeCompletedAnswerRecoverySessions(
    store,
    target,
    view.producedAnswerId ?? historicalRecoveryAnswerId(store, target),
  )
  useInputDraftStore().clearDraft(
    target.projectId,
    target.nodeId,
    target.routeId,
  )
}

/*
 * 单个会话的 FAILED run 对账:canonical 读取裁决回答是否已持久化
 * (→ 修复入口,绝不二次提交),还是什么都没落地(→ 本会话上显式的
 * 一次性重提交载荷)。
 */
export async function reconcileFailedAnswerRunAction(
  store: AnswerRunSlice,
  session?: AnswerRunSessionState,
): Promise<void> {
  const target = session ?? store.focusedAnswerSession
  if (!target) return
  const reconciled = await store.refreshWorkspace()
  if (!isSessionTracked(store, target)) return
  if (!reconciled) {
    target.status = 'UNKNOWN'
    return
  }
  const answerId = historicalRecoveryAnswerId(store, target)
  if (answerId) {
    if (target.historicalRecovery) {
      retainHistoricalRecovery(store, target, answerId)
    } else if (routeTipOf(store, target.routeId) === target.nodeId) {
      target.status = 'REPAIRABLE'
      target.repairableAnswerId = answerId
      store.feedback = '回答已保存，后续生成未完成'
    } else {
      // 末端已越过被回答节点:尽管报告了失败,mutation 实际已完成。
      // 绝不提供重提交或修复。
      removeAnswerSession(store, target)
      store.feedback = '回答已记录'
    }
  } else if (target.historicalRecovery) {
    target.status = 'UNKNOWN'
  } else {
    target.status = 'RESUBMITTABLE'
  }
}

/*
 * 单次尝试无法被观察到达终态(预算之外的网络丢失)之后的对账。canonical
 * 读取在修复(回答已持久化)、其实已完成(末端已前进)与本会话上显式的
 * 未知结果入口之间裁决。绝不自行重提交。
 */
export async function reconcileUnknownAnswerOutcomeAction(
  store: AnswerRunSlice,
  session?: AnswerRunSessionState,
): Promise<void> {
  const target = session ?? store.focusedAnswerSession
  if (!target) return
  const reconciled = await store.refreshWorkspace()
  if (!isSessionTracked(store, target)) return
  if (!reconciled) {
    target.status = 'UNKNOWN'
    return
  }
  const answerId = historicalRecoveryAnswerId(store, target)
  if (answerId) {
    if (target.historicalRecovery) {
      retainHistoricalRecovery(store, target, answerId)
    } else if (routeTipOf(store, target.routeId) === target.nodeId) {
      target.status = 'REPAIRABLE'
      target.repairableAnswerId = answerId
      store.feedback = '回答已保存，后续生成未完成'
    } else {
      removeAnswerSession(store, target)
      store.feedback = '回答已记录'
    }
    return
  }
  // 没有持久化的回答时,run 可能仍在服务端执行:保持 UNKNOWN,让用户去
  // 对账,而不是再制造第二个 mutation。
  target.status = 'UNKNOWN'
}

/*
 * 在允许一次失败的提交再次 mutation 之前,先对账焦点恢复会话(刷新状态
 * 按钮)。只有该会话的入口变化;其它路线上的并发 run 不受影响。
 */
export async function reconcileAnswerOutcomeAction(store: AnswerRunSlice): Promise<boolean> {
  const session = store.focusedAnswerSession
  if (!session || (session.status !== 'RESUBMITTABLE' && session.status !== 'UNKNOWN')) {
    return false
  }
  const previousError = store.error
  const reconciled = await store.refreshWorkspace()
  if (!isSessionTracked(store, session)) return false
  if (!reconciled) {
    session.status = 'UNKNOWN'
    store.error = previousError
    return false
  }
  const answerId = historicalRecoveryAnswerId(store, session)
  if (answerId) {
    if (session.historicalRecovery) {
      retainHistoricalRecovery(store, session, answerId)
    } else if (routeTipOf(store, session.routeId) === session.nodeId) {
      session.status = 'REPAIRABLE'
      session.repairableAnswerId = answerId
      store.feedback = '回答已保存，后续生成未完成'
    } else {
      removeAnswerSession(store, session)
      store.feedback = '回答已记录'
    }
  } else if (session.historicalRecovery) {
    session.status = 'UNKNOWN'
  } else {
    session.repairableAnswerId = null
    session.status = 'RESUBMITTABLE'
  }
  store.error = previousError
  return true
}

/*
 * 经 RESUME_ANSWER run 修复一个已有的回答检查点。后端从持久化的回答
 * 重放原始的 ANSWER_SUBMITTED 语义,因此这绝不会创建第二条回答,前端也
 * 绝不重发它猜测的用户输入副本。修复在自己独立的会话内运行,与并发的
 * 回答 run 隔离。
 */
export async function repairAnswerForActiveFlowAction(
  store: AnswerRunSlice,
  answerId: string,
  recoveryRouteId?: string | null,
  recoveryNodeId?: string | null,
): Promise<boolean> {
  if (!store.projectId || store.repairingAnswer || store.routeCommandPending) return false
  const projectId = store.projectId
  const projectSessionId = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === projectSessionId && store.projectId === projectId
  const historicalRecovery = recoveryRouteId != null || recoveryNodeId != null
  store.repairingAnswer = true
  store.error = null
  const session = reactive<AnswerRunSessionState>({
    clientRequestId: crypto.randomUUID(),
    projectId,
    routeId: recoveryRouteId ?? store.activeState?.activeRoute?.id ?? null,
    nodeId: recoveryNodeId ?? store.activeState?.activeRoute?.tipNodeId ?? '',
    payload: { freeText: null, selectedOptionId: null },
    runId: null,
    phase: null,
    runStatus: 'PENDING',
    status: 'RUNNING',
    repairableAnswerId: historicalRecovery ? answerId : null,
    historicalRecovery,
  })
  store.answerRunSessions.push(session)
  try {
    const sourceRouteId = session.routeId !== store.activeState?.activeRoute?.id
      ? session.routeId
      : null
    const run = await createAgentRun(projectId, {
      operation: 'RESUME_ANSWER',
      nodeId: session.nodeId || null,
      answerId,
      ...(sourceRouteId ? { sourceRouteId } : {}),
    })
    if (!isSessionTracked(store, session)) return false
    session.runId = run.runId
    session.phase = run.phase ?? null
    useRunRegistryStore().register({
      runId: run.runId,
      operation: 'RESUME_ANSWER',
      routeId: session.routeId,
      sourceNodeId: session.nodeId || null,
    })
    await store.pollAnswerRun(run.runId)
    if (isSessionTracked(store, session) && session.status === 'UNKNOWN') {
      store.error = toDisplayError(new ApiError(
        GENERIC_ERROR_MESSAGE, 'UNKNOWN_ERROR', 0))
      return false
    }
    if (isSessionTracked(store, session)
      && (session.status === 'REPAIRABLE' || session.status === 'RESUBMITTABLE')) {
      return false
    }
    if (isCurrent()) {
      store.feedback = '已重新请求后续生成'
    }
    return true
  } catch (err) {
    const safeError = toDisplayError(err)
    let reconciled = false
    try {
      reconciled = await store.refreshWorkspace()
    } catch {
      // 命令结果现在未知;保留历史目标,等待之后的显式对账确立检查点。
      reconciled = false
    }
    if (isSessionTracked(store, session) && historicalRecovery) {
      if (!reconciled) {
        session.status = 'UNKNOWN'
      } else {
        retainHistoricalRecovery(
          store,
          session,
          historicalRecoveryAnswerId(store, session),
        )
      }
      if (isCurrent()) {
        store.error = safeError
      }
      return false
    }
    if (isSessionTracked(store, session) && reconciled) {
      // 回答仍需修复:会话级 repairableAnswerId 已保留;全局 canonical
      // 检查点入口已删除(失败恢复统一走任务级失败清单)。
    }
    // 一次失败的修复绝不留下悬挂的 RUNNING 会话——路线锁绝不能比这次
    // 尝试活得更久。
    removeAnswerSession(store, session)
    if (isCurrent()) {
      store.error = safeError
    }
    return false
  } finally {
    // 只有持有会话的一方释放修复标志:过期修复的清理绝不能释放新会话的
    // 标志。
    if (isCurrent()) {
      store.repairingAnswer = false
    }
  }
}

/** 只有在对账证明回答确实不存在之后才重新提交。 */
export async function resubmitFailedAnswerAction(store: AnswerRunSlice): Promise<boolean> {
  const payload = store.resubmitAnswerPayload
  if (!payload || !store.projectId || store.submitting || store.routeCommandPending) return false
  return store.submitAnswer(payload)
}


export function findFinalizedAnswerForNodeAction(
  store: AnswerRunSlice,
  nodeId: string | null,
  routeId?: string | null,
): string | null {
  // 回答在它被提交到的那条路线上查找。多路线工作下那条路线不是 Active
  // 路线,因此搜索 Active 路线的回答会错误报告"什么都没落地",并对一条
  // 已经存在的回答提供重提交。
  const lookupRouteId = routeId
    ?? store.submittedRouteIdForCleanup
    ?? store.activeState?.activeRoute?.id
    ?? null
  if (!lookupRouteId || !nodeId) return null
  return store.graphView?.answers.find((answer) =>
    answer.routeId === lookupRouteId
    && answer.nodeId === nodeId
    && answer.inherited === false
    && answer.ownerRouteId === lookupRouteId,
  )?.id ?? null
}

/*
 * 在途回答被提交到的那条路线的实时末端,从 canonical 图读取。
 *
 * 绝不是 Active 指针:它可能已经前进,或者——多路线工作下——指向与
 * 被回答路线完全不同的一条路线。
 */
export function answerTargetRouteTipAction(store: AnswerRunSlice): string | null {
  const routeId = store.submittedRouteIdForCleanup
  if (!routeId) return null
  return store.graphView?.routes.find((route) => route.id === routeId)?.tipNodeId ?? null
}


export function setFocusAfterMutationAction(
  store: AnswerRunSlice,
  target: MutationFocusTarget | null,
): void {
  store.focusAfterMutation = target
}

export function consumeFocusAfterMutationAction(store: AnswerRunSlice): MutationFocusTarget | null {
  const target = store.focusAfterMutation
  store.focusAfterMutation = null
  return target
}

export async function retryManualModelOperationAction(store: AnswerRunSlice): Promise<boolean> {
  const intent = store.manualModelRetry
  if (!intent) return false
  const projectId = store.projectId
  const projectSessionId = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === projectSessionId && store.projectId === projectId
  if (intent.state === 'ambiguous') {
    const previousError = store.error
    await store.refreshWorkspace()
    // 过期守卫:旧会话的重试绝不能把它的错误写进新的项目纪元。
    if (!isCurrent()) return false
    store.error = previousError
    return false
  }
  if (intent.state === 'needs_reconcile') {
    const previousError = store.error
    if (intent.kind === 'draft') {
      const reconciled = await store.refreshWorkspace()
      // 显式路线的起草对"目标路线的 tip"核对，而不是 Active 指针；
      // 目标路线已不存在时退回原来的 Active 语义比较。
      const targetRoute = intent.beforeRouteId
        ? store.graphView?.routes.find((route) => route.id === intent.beforeRouteId) ?? null
        : null
      const afterRouteId = targetRoute?.id
        ?? store.activeState?.activeRoute?.id ?? null
      const afterTipNodeId = targetRoute
        ? targetRoute.tipNodeId
        : store.activeState?.activeRoute?.tipNodeId ?? null
      if (!reconciled) {
        store.error = previousError
        return false
      }
      if (
        afterRouteId !== intent.beforeRouteId
        || afterTipNodeId !== intent.beforeTipNodeId
      ) {
        store.manualModelRetry = null
        store.error = null
        store.feedback = '问题已起草'
        return true
      }
      store.manualModelRetry = { ...intent, state: 'ready' }
      store.error = previousError
      return false
    }
    if (intent.kind === 'spec') return store.reconcileSpecRetry(intent)
    return store.reconcileRegenerateRetry(intent)
  }
  if (intent.kind === 'draft') return store.draftQuestion(intent.beforeRouteId ?? undefined)
  if (intent.kind === 'spec') {
    return await store.generateSpec()
  }
  return store.regenerateNode(intent.nodeId, intent.payload)
}

/**
 * 重试终态后的共享收尾(第三轮复核 R3-B 的闭合):主动重试
 * (retryFailedRunAction)与硬刷新后由服务端清单续接的 watcher
 * (workspaceLoader.resumeInFlightRetryWatches)共用这同一条完成路径——
 * canonical 刷新(图/规格/需求状态)、失败清单对账、乐观标记清理与按操作
 * 的节点查询结果刷新。成功后新产物自动可见,恢复提示正确消失,不依赖发起
 * 重试的那个页面还活着;失败/unknown 由服务端清单的最新失败接替,unknown
 * 不伪装成终态。每次写入之前都重新校验会话身份,旧项目纪元绝不污染新纪元。
 *
 * @param failure 被重试的失败任务(服务端判定的未解决失败身份)
 * @param retryRunId 重试 run id(主动重试为新建 run;续接为清单里的 retryRunId)
 * @param outcome pollRunChainToTerminal 的终态结果
 * @returns 成功恢复返回 true;失败/结果未知/会话过期返回 false
 */
export async function finalizeRetryCompletionAction(
  store: AnswerRunSlice,
  failure: UnresolvedFailure,
  retryRunId: string,
  outcome: AgentRunView | 'failed' | 'unknown',
): Promise<boolean> {
  const registry = useRunRegistryStore()
  const projectId = store.projectId
  const projectSessionId = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === projectSessionId && store.projectId === projectId
  // canonical 刷新:新节点/规格/需求状态立即进入页面(不需要用户再次刷新)。
  // 刷新内部已与失败清单对账一次;这里再显式对账,保证刷新失败时提示仍收敛。
  await store.refreshWorkspace()
  if (!isCurrent()) return false
  await store.rebuildUnresolvedFailures()
  if (!isCurrent()) return false
  if (outcome === 'failed' || outcome === 'unknown') {
    // 失败/结果未知:乐观标记回滚。结果未知同样由服务端清单对账
    // (rebuild 已同步 retryRunId),绝不留下永久禁用的旧入口,也绝不把
    // unknown 当作成功或终态展示。
    registry.clearRetryMark(failure.runId)
    return false
  }
  registry.clearFailure(failure.runId)
  // 节点查询重试成功:检查器立即展示新 run 的真实结果(6-5),绝不
  // 只显示"恢复成功"而把检查器留在旧失败结果上。
  if (failure.availableAction === 'RETRY_NODE_QUERY' && failure.sourceNodeId
      && store.nodeQuery?.runId === failure.runId) {
    await store.refreshNodeQueryResult(failure.sourceNodeId, retryRunId, {
      routeId: store.nodeQuery.routeId,
      question: store.nodeQuery.question,
    })
    if (!isCurrent()) return false
  }
  store.feedback = '已从上次失败处恢复'
  return true
}

/**
 * 任务级失败恢复:从失败任务身份(服务端判定的未解决失败)发起重试。
 * 身份、原始意图与恢复资格全部由服务端持久化事实决定——前端只提交
 * "重试哪个失败任务",绝不本地重建 payload,也绝不回落 Active 路线。
 * 幂等性由后端确定性键保证:双击/跨标签页的重复重试返回同一个 run。
 *
 * 即时状态(6-2):乐观 retrying 标记在请求发出之前就写入——按钮立即
 * 禁用、立即显示进度,不等待网络往返;失败/结果未知/会话切换都有对应
 * 的清理路径,后端幂等仍然保留。
 */
export async function retryFailedRunAction(
  store: AnswerRunSlice,
  failure: UnresolvedFailure,
): Promise<boolean> {
  const registry = useRunRegistryStore()
  if (registry.isRetrying(failure.runId)) return false
  if (!failure.runId) return false
  const projectId = store.projectId
  if (!projectId) return false
  // 请求发出前立即防重复并显示进度:延迟响应/双击/慢网络下按钮状态
  // 必须先于网络往返生效。
  registry.markRetryPending(failure.runId)
  const projectSessionId = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === projectSessionId && store.projectId === projectId
  try {
    const created = await retryAgentRun(projectId, failure.runId)
    if (!isCurrent()) return false
    registry.markRetryStarted(failure.runId, created.runId)
    registry.register({
      runId: created.runId,
      operation: failure.availableAction === 'CONTINUE_PROCESSING' ? 'RESUME_ANSWER' : failure.operation,
      routeId: failure.routeId,
      sourceNodeId: failure.sourceNodeId,
    })
    const outcome = await store.pollRunChainToTerminal(created.runId)
    if (!isCurrent()) return false
    // 重试完成:与刷新续接的 watcher 共用同一条收尾(见上)。
    return await finalizeRetryCompletionAction(store, failure, created.runId, outcome)
  } catch (err) {
    if (!isCurrent()) return false
    registry.clearRetryMark(failure.runId)
    if (err instanceof ApiError && err.code === 'RECOVERY_IN_FLIGHT') {
      // 另一个标签页/会话已发起重试:对账并显示进度,绝不重复提交。
      await store.rebuildUnresolvedFailures()
      return false
    }
    store.error = toDisplayError(err)
    return false
  }
}
