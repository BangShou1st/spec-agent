/**
 * Workspace runs domain: the async Agent Runtime lifecycle.
 *
 * Drafting, answering, polling run chains to a terminal leaf, reconciling the
 * fail-closed outcome states, and the manual retry affordances the UI surfaces
 * after a failed or unknown run. Also owns the answer-recovery readers and the
 * post-mutation focus handoff they feed.
 *
 * Every function is the verbatim action body lifted out of `workspaceStore.ts`
 * with `this` replaced by the store instance passed in as the first argument.
 * The store keeps the action names and delegates, so no caller changes.
 */
import { reactive } from 'vue'
import { sleep } from '@/shared/lib/timing'
import {
  AGENT_RUN_MAX_POLLS,
  AGENT_RUN_POLL_INTERVAL_MS,
  createAgentRun,
  getAgentRun,
  isTerminalRunStatus,
} from '@/features/workspace/api/agentRuns'
import type { AgentRunView } from '@/features/workspace/api/agentRuns'
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

// ---- Answer-run session helpers ---------------------------------------------
//
// Every answer attempt owns an `AnswerRunSessionState` entry on the store.
// All lifecycle writes below go through the session object — never through
// the store's derived single-value getters — so two concurrent answers on
// different routes are fully isolated.

/** True while the session is still tracked by THIS store instance. A project
 * switch (beginProject) drops all sessions; a detached session must never
 * write feedback/error into the NEW project. */
function isSessionTracked(store: AnswerRunSlice, session: AnswerRunSessionState): boolean {
  return store.answerRunSessions.includes(session)
}

function removeAnswerSession(store: AnswerRunSlice, session: AnswerRunSessionState): void {
  const index = store.answerRunSessions.indexOf(session)
  if (index >= 0) store.answerRunSessions.splice(index, 1)
}

/**
 * A successful recovery supersedes only stale sessions for the exact answer
 * target. Other answers/routes remain visible, and a separate run that is
 * still in flight is never cancelled by another run's terminal observation.
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

/** Live tip of one explicit route, read from the canonical graph. Never the
 * Active pointer: under multi-route work it may name a different route. */
function routeTipOf(store: AnswerRunSlice, routeId: string | null): string | null {
  if (!routeId) return null
  return store.graphView?.routes.find((route) => route.id === routeId)?.tipNodeId ?? null
}

function findSessionByRunId(store: AnswerRunSlice, runId: string): AnswerRunSessionState | null {
  return store.answerRunSessions.find((session) => session.runId === runId) ?? null
}

/**
 * Canonical read: does the route this session was submitted to carry a
 * finalized Answer for the answered node? Under multi-route work the route
 * is NOT the Active route, so searching the Active route's answers would
 * report "nothing landed" and offer a resubmit for an answer that exists.
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

/**
 * Drafts the next question through the async Agent Runtime. Explicit user
 * action only; a fresh project enqueues no run until this fires.
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
  // Draft identity: the project (and its session) captured when the user
  // action started. After EVERY await the identity is re-validated before
  // any store write, so a slow draft can never leak its error/feedback or
  // clear its projection into a different project session.
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
      // A terminal RESPOND leaf carries the user-visible message; a
      // graph-mutation leaf keeps the existing draft confirmation copy.
      store.feedback = store.pendingDraftRespondMessage ?? '问题已起草'
      const refreshed = await store.refreshWorkspace()
      if (!isCurrent()) return false
      if (refreshed) store.pendingRouteProjection = null
      store.manualModelRetry = null
      return true
    }
    // FAILED or outcome unknown: reconcile against canonical reads, then
    // surface the retry affordance keyed to the pre-draft graph state.
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
      // The draft actually landed (e.g. the run finished after the last
      // poll); never offer a retry that would double-draft.
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
    // The create-run request itself failed; the run may or may not exist.
    // Reconcile canonical state before allowing a retry.
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
    // Only the owning session releases the draft flag: a stale draft's
    // cleanup must not release the NEW session's flag (beginProject has
    // already reset it there).
    if (isCurrent()) {
      store.drafting = false
    }
  }
}

/**
 * Polls one run to its terminal state and returns the final read view
 * (with the produced record ids), 'failed' for a FAILED terminal status,
 * or 'unknown' when no terminal read happened within the budget. Stops
 * observing when the project switches.
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
      // Post-await identity check: the read may have resolved after the
      // user switched projects — never feed the stale run then.
      if (!isCurrent()) return 'unknown'
      useRunRegistryStore().feed(view)
      onView?.(view)
      if (!isTerminalRunStatus(view.status)) continue
      return view.status === 'completed' ? view : 'failed'
    } catch {
      // Transient poll failure: keep polling within budget.
    }
  }
  return 'unknown'
}

/**
 * Follows one autonomous run chain to its terminal leaf. A COMPLETED run
 * with a childRunId continues on the child; a COMPLETED run with no
 * child but a pending continuation check keeps polling the same run
 * until the dispatcher/recovery creates the child. The poll budget is
 * shared across the whole chain so a long chain cannot poll forever.
 * Returns the terminal leaf view, 'failed' for a FAILED leaf, or
 * 'unknown' when the budget ran out or the project switched.
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
      // Post-await identity check: the read may have resolved after the
      // user switched projects — never follow the stale chain then.
      if (!isCurrent()) return 'unknown'
      useRunRegistryStore().feed(view)
      onView?.(view)
      if (!isTerminalRunStatus(view.status)) continue
      if (view.status === 'failed') return 'failed'
      if (view.childRunId) {
        currentRunId = view.childRunId
        continue
      }
      if (view.continuationPending) continue
      return view
    } catch {
      // Transient poll failure: keep polling within budget.
    }
  }
  return 'unknown'
}

/**
 * Polls one question-draft run chain to a terminal leaf. Drafting has no
 * immutable-input concerns: 'completed' refreshes canonical state in the
 * caller, anything else reconciles.
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
  // Post-await identity check: a stale poll must not write its respond
  // message into a different project session.
  if (store.projectSessionId !== projectSessionId) return 'unknown'
  if (outcome === 'unknown' || outcome === 'failed') return outcome
  if (outcome.respondMessage) {
    store.pendingDraftRespondMessage = outcome.respondMessage
  }
  return 'completed'
}

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
export async function submitAnswerAction(
  store: AnswerRunSlice,
  payload: SubmitAnswerRequest,
): Promise<boolean> {
  if (!store.projectId || store.routeCommandPending) {
    return false
  }
  // The whole attempt is bound to the project identity captured HERE.
  const projectId = store.projectId
  // Target resolution: an explicit target (the tip of the route the user
  // is reading) wins; otherwise the Active route's current node — the
  // original behaviour, unchanged.
  const activeRouteId = store.activeState?.activeRoute?.id ?? null
  const answeringNodeId = payload.nodeId
    ?? store.activeState?.activeNode?.id
    ?? store.activeState?.activeRoute?.tipNodeId
    ?? null
  const submittedRouteId = payload.routeId ?? activeRouteId
  // One in-flight answer run PER ROUTE: another route's chain must never
  // block this one (that is the whole point of independent routes), while
  // the same route can never have two competing answer cycles.
  if (!answeringNodeId || (submittedRouteId !== null
    && store.answerRunsInFlight.includes(submittedRouteId))) {
    return false
  }
  // Submission identity is fixed when the user action starts: the node
  // being answered and its route at that moment. Success cleanup uses
  // exactly these — never produced ids or post-refresh route pointers.
  const submittedNodeId = answeringNodeId
  // One stable idempotency identity per user action attempt: unknown-
  // outcome retries (create request lost, response lost) reuse the same
  // key so the backend returns the already-created run.
  const clientRequestId = crypto.randomUUID()

  // A NEW attempt for the SAME target (same project + route + node)
  // replaces the previous attempt's recovery affordances — that previous
  // session is exactly what the user is retrying. Sessions of OTHER
  // targets (other routes/nodes) are never touched: one run must never
  // clear or overwrite another run's error or recovery state.
  store.answerRunSessions = store.answerRunSessions.filter((existing) => {
    const sameTarget = existing.projectId === projectId
      && existing.nodeId === submittedNodeId
      && (existing.routeId ?? null) === (submittedRouteId ?? null)
    return !(sameTarget && existing.status !== 'RUNNING')
  })

  // `reactive()` so the store's derived getters (answerRunId, submitting,
  // recovery affordances) react to in-place session lifecycle updates — a
  // raw object pushed into the reactive array would mutate silently.
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
    // The backend routes an ANSWER_TIP whose node already carries a
    // persisted Answer to RESUME_ANSWER itself; the frontend never
    // guesses which one applies.
    //
    // EXPLICIT route mode is requested ONLY when the target is not the
    // Active route: that keeps the Active path's fail-closed guarantee
    // (a run whose Active pointer moved must still fail) untouched.
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
      // Polling ended without a terminal read (network loss beyond the
      // budget). Reconcile canonical state; never auto-resubmit.
      await reconcileUnknownAnswerOutcomeAction(store, session)
    }
    // The poll settled the session in place: it is gone on a fully-handled
    // success, or carries its own recovery status (REPAIRABLE /
    // RESUBMITTABLE / UNKNOWN) that belongs to THIS attempt only.
    return !isSessionTracked(store, session)
  } catch (err) {
    if (!isSessionTracked(store, session)) return false
    const safeError = toDisplayError(err)
    if (!created) {
      // The create-run request itself failed or its outcome is unknown.
      // Reconcile against canonical reads before ever allowing a second
      // mutation: only a proven absent Answer + no run may resubmit.
      const reconciled = await store.refreshWorkspace()
      if (!isSessionTracked(store, session)) return false
      if (!reconciled) {
        session.status = 'UNKNOWN'
        store.error = safeError
        return false
      }
      const answerId = findFinalizedAnswerForSession(store, session)
      if (answerId) {
        // An Answer was already persisted (the create request may have
        // landed even though its response was lost). Never resubmit —
        // surface repair instead.
        if (routeTipOf(store, session.routeId) === session.nodeId) {
          session.status = 'REPAIRABLE'
          session.repairableAnswerId = answerId
          store.feedback = '回答已保存，后续生成未完成'
          store.error = withAnswerableNodeHint(
            safeError,
            store.activeState?.activeNode?.question ?? null,
          )
        } else {
          // The tip moved past the answered node: the mutation completed.
          removeAnswerSession(store, session)
          store.feedback = '回答已记录'
          store.error = null
        }
      } else {
        // Canonical reads prove: no Answer, and the run was never
        // created. A one-shot resubmit is now provably safe.
        session.status = 'RESUBMITTABLE'
        store.error = withAnswerableNodeHint(
          safeError,
          store.activeState?.activeNode?.question ?? null,
        )
      }
      return false
    }
    // Run was created but polling ended without a terminal read (budget
    // exhausted on network loss). Do NOT resubmit: reconcile instead.
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

/**
 * Polls one answer run chain to its terminal leaf. The polled session is
 * resolved by run id and ALL observations are written onto that session —
 * concurrent answer runs each own their poll loop and never overwrite each
 * other's phase/status. The loop stops observing as soon as the session is
 * no longer tracked (project switch / workspace reload), checked both after
 * the sleep AND after each awaited read. Only the terminal leaf decides
 * success: an intermediate COMPLETED parent with a child must never finish
 * early.
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
        // Project switched away or workspace reloaded: stop observing.
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
        // FAILED run: the Answer may or may not be persisted. Canonical
        // reads decide between repair and resubmit affordances.
        await store.reconcileFailedAnswerRun(session)
        return
      }
      if (view.childRunId) {
        currentRunId = view.childRunId
        continue
      }
      if (view.continuationPending) continue
      if (session.historicalRecovery && !view.producedPatchId) {
        // A historical checkpoint is successful only when the terminal run
        // reports the Patch it was meant to recover. A completed status alone
        // must not erase the retry affordance.
        await store.reconcileFailedAnswerRun(session)
        return
      }
      await store.finishSuccessfulAnswerRun(view, session)
      return
    } catch {
      // Transient poll failure: keep polling within budget.
      if (!isSessionTracked(store, session)) return
    }
  }
  // Budget exhausted with no terminal read: this attempt's outcome is
  // unknown; the caller reconciles against canonical reads.
  if (isSessionTracked(store, session)) {
    session.status = 'UNKNOWN'
  }
}

/**
 * Terminal chain leaf of ONE answer attempt: refresh canonical state and
 * settle exactly this session. Cleanup identity is the SUBMITTED answer
 * target captured when the user action started — never producedNodeId,
 * which names the NEXT node the runtime generated, and never a route id
 * re-read after refresh. Other sessions (other routes' runs) are untouched.
 */
export async function finishSuccessfulAnswerRunAction(
  store: AnswerRunSlice,
  view: Awaited<ReturnType<typeof getAgentRun>>,
  session?: AnswerRunSessionState,
): Promise<void> {
  const target = session ?? findSessionByRunId(store, view.runId)
  if (!target) return
  const leafMessage = view.respondMessage ?? null
  // Session guard BEFORE the first write: if the project already switched,
  // this terminal leaf must not write its feedback into the new era.
  if (!isSessionTracked(store, target)) return
  if (target.historicalRecovery && !view.producedPatchId) {
    retainHistoricalRecovery(store, target, historicalRecoveryAnswerId(store, target))
    return
  }
  store.feedback = leafMessage ?? '回答已记录'
  await store.refreshWorkspace()
  if (!isSessionTracked(store, target)) return
  // A draft retry intent is only cleared when it belongs to THIS route —
  // a concurrent draft retry on another route is not this run's business.
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

/**
 * FAILED run reconciliation for ONE session: canonical reads decide whether
 * the Answer persisted (→ repair affordance, never a second submission) or
 * nothing landed (→ explicit one-shot resubmit payload on this session).
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
      // The tip moved past the answered node: the mutation completed
      // despite the failure report. Never offer resubmit or repair.
      removeAnswerSession(store, target)
      store.feedback = '回答已记录'
    }
  } else if (target.historicalRecovery) {
    target.status = 'UNKNOWN'
  } else {
    target.status = 'RESUBMITTABLE'
  }
}

/**
 * Reconciliation after ONE attempt could not be observed to a terminal
 * state (poll network loss beyond the budget). Canonical reads decide
 * between repair (Answer persisted), completed-anyway (tip advanced), and
 * an explicit unknown-outcome affordance on this session. Never resubmits
 * by itself.
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
  // Without a persisted Answer the run may still be executing server
  // side: keep UNKNOWN so the user reconciles instead of creating a
  // second mutation.
  target.status = 'UNKNOWN'
}

/**
 * Reconciles the focused recovery session (刷新状态 button) before allowing
 * a failed submit to mutate again. Only this session's affordances change;
 * concurrent runs on other routes are untouched.
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

/**
 * Repairs an existing answer checkpoint through a RESUME_ANSWER run. The
 * backend replays the original ANSWER_SUBMITTED semantics from the
 * persisted Answer, so this never creates a second Answer and the
 * frontend never re-sends its guessed copy of the user input. The repair
 * runs inside its own session, isolated from concurrent answer runs.
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
      // The command outcome is now unknown; preserve the historical target
      // until a later explicit reconciliation can establish the checkpoint.
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
      // The answer still needs repair; refresh the canonical checkpoint.
      store.canonicalRepairableAnswerId = store.findFinalizedAnswerForActiveTip()
    }
    // A failed repair leaves no dangling RUNNING session behind — the
    // route lock must never outlive the attempt.
    removeAnswerSession(store, session)
    if (isCurrent()) {
      store.error = safeError
    }
    return false
  } finally {
    // Only the owning session releases the repair flag: a stale repair's
    // cleanup must not release the NEW session's flag.
    if (isCurrent()) {
      store.repairingAnswer = false
    }
  }
}

/** Re-submits only after reconciliation proved that the Answer was absent. */
export async function resubmitFailedAnswerAction(store: AnswerRunSlice): Promise<boolean> {
  const payload = store.resubmitAnswerPayload
  if (!payload || !store.projectId || store.submitting || store.routeCommandPending) return false
  return store.submitAnswer(payload)
}

export function findFinalizedAnswerForActiveTipAction(store: AnswerRunSlice): string | null {
  const activeRoute = store.activeState?.activeRoute
  const tipNodeId = activeRoute?.tipNodeId
  if (!activeRoute || !tipNodeId) return null
  return store.graphView?.answers.find((answer) =>
    answer.nodeId === tipNodeId
    && answer.routeId === activeRoute.id
    && answer.inherited === false
    && answer.ownerRouteId === activeRoute.id,
  )?.id ?? null
}

export function findFinalizedAnswerForNodeAction(
  store: AnswerRunSlice,
  nodeId: string | null,
  routeId?: string | null,
): string | null {
  // The answer is looked up on the route it was submitted to. Under
  // multi-route work that route is NOT the Active route, so searching the
  // Active route's answers would report "nothing landed" and offer a
  // resubmit for an answer that already exists.
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

/**
 * Live tip of the route the in-flight answer was submitted to, read from
 * the canonical graph.
 *
 * Never the Active pointer: it may have moved on, or — under multi-route
 * work — may name a completely different route than the one being
 * answered.
 */
export function answerTargetRouteTipAction(store: AnswerRunSlice): string | null {
  const routeId = store.submittedRouteIdForCleanup
  if (!routeId) return null
  return store.graphView?.routes.find((route) => route.id === routeId)?.tipNodeId ?? null
}

export function findForkDraftRetryRouteIdAction(store: AnswerRunSlice): string | null {
  const activeRoute = store.activeState?.activeRoute
  const graphRoute = activeRoute
    ? store.graphView?.routes.find((route) => route.id === activeRoute.id)
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
  const tipAnswers = store.graphView?.answers.filter((answer) =>
    answer.routeId === graphRoute.id && answer.nodeId === tipNodeId,
  ) ?? []
  return tipAnswers.length === 1
    && tipAnswers[0].inherited === true
    && tipAnswers[0].ownerRouteId !== graphRoute.id
    ? graphRoute.id
    : null
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
    // Stale guard: the old session's retry must not write its error into
    // the new project era.
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

export async function retryForkDraftAction(store: AnswerRunSlice): Promise<boolean> {
  const retryRouteId = store.forkDraftRetryRouteId
  if (!retryRouteId || store.routeCommandPending || store.drafting) {
    return false
  }
  const projectId = store.projectId
  const projectSessionId = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === projectSessionId && store.projectId === projectId
  const activeRoute = store.activeState?.activeRoute
  const retryRoute = store.graphView?.routes.find((route) => route.id === retryRouteId)
  if (activeRoute?.id !== retryRouteId || retryRoute?.lifecycleStatus !== 'open') {
    store.error = {
      code: 'FORK_DRAFT_RETRY_REQUIRES_ACTIVE_ROUTE',
      message: '请先将该分支设为当前路线，再重试起草',
    }
    return false
  }
  const drafted = store.manualModelRetry?.kind === 'draft'
    ? await store.retryManualModelOperation()
    : await store.draftQuestion()
  // Stale guard: the awaited retry/draft may have outlived the session it
  // was started in — never write its cleanup into the new era.
  if (!isCurrent()) return drafted
  if (drafted) {
    store.forkDraftRetryRouteId = null
    store.setFocusAfterMutation({
      routeId: retryRouteId,
      nodeId: store.activeState?.activeRoute?.tipNodeId ?? null,
    })
    store.feedback = '已起草分支的首个后续问题'
  }
  return drafted
}

/** Retry the visible pending projection without inventing a provider or
 * issuing a second mutation unless Runtime recovery has proven it safe. */
export async function retryPendingAgentRunAction(store: AnswerRunSlice): Promise<boolean> {
  if (store.forkDraftRetryRouteId) return store.retryForkDraft()
  if (store.manualModelRetry?.kind === 'draft') {
    return store.retryManualModelOperation()
  }
  return store.draftQuestion()
}
