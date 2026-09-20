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
import { sleep } from '@/composables/timing'
import {
  AGENT_RUN_MAX_POLLS,
  AGENT_RUN_POLL_INTERVAL_MS,
  createAgentRun,
  getAgentRun,
  isTerminalRunStatus,
} from '@/api/agentRuns'
import type { AgentRunView } from '@/api/agentRuns'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import { toDisplayError } from '@/api/displayError'
import { classifyModelFailure } from '@/api/errorCopy'
import { useInputDraftStore } from '@/stores/inputDraftStore'
import { useRunRegistryStore } from '@/stores/runRegistryStore'
import type { GraphPendingProjection } from '@/graph/graphProjection'
import type { SubmitAnswerRequest } from '@/api/types'
import type { WorkspaceStore } from '../workspaceStore'
import type { ManualModelRetryIntent, MutationFocusTarget } from './types'
import { withAnswerableNodeHint } from './shared'

export function updatePendingRouteProjectionAction(store: WorkspaceStore, view: AgentRunView): void {
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
  store: WorkspaceStore,
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
  store: WorkspaceStore,
  explicitRouteId?: string,
): Promise<boolean> {
  if (!store.projectId || store.drafting || store.routeCommandPending) {
    return false
  }
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
    const run = await createAgentRun(store.projectId, {
      operation: 'DRAFT_QUESTION',
      sourceRouteId: explicitRouteId && explicitRouteId !== activeRouteId
        ? explicitRouteId
        : null,
    })
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
    if (outcome === 'completed') {
      // A terminal RESPOND leaf carries the user-visible message; a
      // graph-mutation leaf keeps the existing draft confirmation copy.
      store.feedback = store.pendingDraftRespondMessage ?? '问题已起草'
      const refreshed = await store.refreshWorkspace()
      if (refreshed) store.pendingRouteProjection = null
      store.manualModelRetry = null
      return true
    }
    // FAILED or outcome unknown: reconcile against canonical reads, then
    // surface the retry affordance keyed to the pre-draft graph state.
    // 目标路线的 tip 是否前进是"草稿已落地"的判据；显式路线同样成立。
    const reconciled = await store.refreshWorkspace()
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
    const safeError = toDisplayError(err)
    store.error = safeError
    const reconciled = await store.refreshWorkspace()
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
    store.drafting = false
  }
}

/**
 * Polls one run to its terminal state and returns the final read view
 * (with the produced record ids), 'failed' for a FAILED terminal status,
 * or 'unknown' when no terminal read happened within the budget. Stops
 * observing when the project switches.
 */
export async function pollRunToTerminalAction(
  store: WorkspaceStore,
  runId: string,
  onView?: (view: AgentRunView) => void,
): Promise<AgentRunView | 'failed' | 'unknown'> {
  const projectId = store.projectId
  if (!projectId) return 'unknown'
  for (let attempt = 0; attempt < AGENT_RUN_MAX_POLLS; attempt += 1) {
    if (attempt > 0) {
      await sleep(AGENT_RUN_POLL_INTERVAL_MS)
      if (projectId !== store.projectId) {
        return 'unknown'
      }
    }
    try {
      const view = await getAgentRun(projectId, runId)
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
  store: WorkspaceStore,
  rootRunId: string,
  onView?: (view: AgentRunView) => void,
): Promise<AgentRunView | 'failed' | 'unknown'> {
  const projectId = store.projectId
  if (!projectId) return 'unknown'
  let currentRunId = rootRunId
  for (let attempt = 0; attempt < AGENT_RUN_MAX_POLLS; attempt += 1) {
    if (attempt > 0) {
      await sleep(AGENT_RUN_POLL_INTERVAL_MS)
      if (projectId !== store.projectId) {
        return 'unknown'
      }
    }
    try {
      const view = await getAgentRun(projectId, currentRunId)
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
  store: WorkspaceStore,
  runId: string,
): Promise<'completed' | 'failed' | 'unknown'> {
  const outcome = await store.pollRunChainToTerminal(
    runId,
    (view) => store.updatePendingRouteProjection(view),
  )
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
  store: WorkspaceStore,
  payload: SubmitAnswerRequest,
): Promise<boolean> {
  if (!store.projectId || store.routeCommandPending) {
    return false
  }
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

  store.submitting = true
  if (submittedRouteId !== null) {
    store.answerRunsInFlight = [...store.answerRunsInFlight, submittedRouteId]
  }
  store.error = null
  store.repairableAnswerId = null
  store.resubmitAnswerPayload = null
  store.pendingAnswerNodeId = answeringNodeId
  store.answerRunId = null
  store.answerRunPhase = null
  store.answerRunStatus = null
  store.answerOutcomeUnknown = false
  store.lastSubmittedAnswerPayload = { ...payload }
  store.submittedRouteIdForCleanup = submittedRouteId

  let created = false
  try {
    // The backend routes an ANSWER_TIP whose node already carries a
    // persisted Answer to RESUME_ANSWER itself; the frontend never
    // guesses which one applies.
    //
    // EXPLICIT route mode is requested ONLY when the target is not the
    // Active route: that keeps the Active path's fail-closed guarantee
    // (a run whose Active pointer moved must still fail) untouched.
    const run = await createAgentRun(store.projectId, {
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
    created = true
    store.answerRunId = run.runId
    store.answerRunStatus = 'RUNNING'
    useRunRegistryStore().register({
      runId: run.runId,
      operation: 'ANSWER_TIP',
      routeId: submittedRouteId,
      sourceNodeId: submittedNodeId,
    })
    await store.pollAnswerRun(run.runId)
    if (store.answerOutcomeUnknown) {
      // Polling ended without a terminal read (network loss beyond the
      // budget). Reconcile canonical state; never auto-resubmit.
      await store.reconcileUnknownAnswerOutcome()
      return false
    }
    return store.pendingAnswerNodeId === null
  } catch (err) {
    const safeError = toDisplayError(err)
    if (!created) {
      // The create-run request itself failed or its outcome is unknown.
      // Reconcile against canonical reads before ever allowing a second
      // mutation: only a proven absent Answer + no run may resubmit.
      const reconciled = await store.refreshWorkspace()
      let canonicalMutationCompleted = false
      if (!reconciled) {
        store.answerOutcomeUnknown = true
        store.resubmitAnswerPayload = { ...payload }
      } else {
        const answerId = store.findFinalizedAnswerForNode(answeringNodeId)
        if (answerId) {
          // An Answer was already persisted (the create request may have
          // landed even though its response was lost). Never resubmit —
          // surface repair instead.
          if (store.activeState?.activeRoute?.tipNodeId === answeringNodeId) {
            store.repairableAnswerId = answerId
            store.feedback = '回答已保存，后续生成未完成'
          } else {
            store.pendingAnswerNodeId = null
            store.feedback = '回答已记录'
            store.error = null
            canonicalMutationCompleted = true
          }
          store.resubmitAnswerPayload = null
        } else {
          // Canonical reads prove: no Answer, and the run was never
          // created. A one-shot resubmit is now provably safe.
          store.resubmitAnswerPayload = { ...payload }
        }
      }
      if (!canonicalMutationCompleted) {
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
    if (!reconciled) {
      store.answerOutcomeUnknown = true
      store.error = safeError
      return false
    }
    const answerId = store.findFinalizedAnswerForNode(store.pendingAnswerNodeId)
    if (answerId && store.answerTargetRouteTip() === store.pendingAnswerNodeId) {
      store.repairableAnswerId = answerId
      store.resubmitAnswerPayload = null
      store.feedback = '回答已保存，后续生成未完成'
    } else {
      store.answerOutcomeUnknown = true
    }
    store.error = safeError
    return false
  } finally {
    if (submittedRouteId !== null) {
      store.answerRunsInFlight = store.answerRunsInFlight.filter((id) => id !== submittedRouteId)
    }
    store.submitting = store.answerRunsInFlight.length > 0
  }
}

/**
 * Polls one answer run chain to its terminal leaf. One loop per call —
 * the same run never gets two timers because submit guards on
 * `submitting`. Network failures inside the loop keep polling within the
 * shared chain attempt budget; exhausting it surfaces an unknown outcome
 * for reconciliation instead of re-submitting anything. Stops observing
 * when the project switches. Only the terminal leaf decides success:
 * an intermediate COMPLETED parent with a child must never finish early.
 */
export async function pollAnswerRunAction(store: WorkspaceStore, runId: string): Promise<void> {
  const projectId = store.projectId
  if (!projectId) return
  let currentRunId = runId
  for (let attempt = 0; attempt < AGENT_RUN_MAX_POLLS; attempt += 1) {
    if (attempt > 0) {
      await sleep(AGENT_RUN_POLL_INTERVAL_MS)
      if (projectId !== store.projectId) {
        // Project switched away: stop observing the old project's run.
        return
      }
    }
    try {
      const view = await getAgentRun(projectId, currentRunId)
      useRunRegistryStore().feed(view)
      store.answerRunPhase = view.phase
      store.answerRunStatus = view.status === 'failed'
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
        await store.reconcileFailedAnswerRun()
        return
      }
      if (view.childRunId) {
        currentRunId = view.childRunId
        continue
      }
      if (view.continuationPending) continue
      await store.finishSuccessfulAnswerRun(view)
      return
    } catch {
      // Transient poll failure: keep polling within budget.
    }
  }
  // Budget exhausted with no terminal read: treat as outcome unknown.
  store.answerOutcomeUnknown = true
}

/** Terminal chain leaf: refresh canonical state and clear pending affordances. */
export async function finishSuccessfulAnswerRunAction(
  store: WorkspaceStore,
  view: Awaited<ReturnType<typeof getAgentRun>>,
): Promise<void> {
  // Cleanup identity is the SUBMITTED answer target captured when the
  // user action started — never producedNodeId, which names the NEXT node
  // the runtime generated, and never a route id re-read after refresh.
  const answeredNodeId = store.pendingAnswerNodeId
  const submittedRouteId = store.submittedRouteIdForCleanup ?? null
  const leafMessage = view.respondMessage ?? null
  store.feedback = leafMessage ?? '回答已记录'
  await store.refreshWorkspace()
  store.manualModelRetry = null
  store.repairableAnswerId = null
  store.resubmitAnswerPayload = null
  store.pendingAnswerNodeId = null
  store.answerOutcomeUnknown = false
  store.answerRunId = null
  store.answerRunPhase = null
  store.answerRunStatus = null
  if (answeredNodeId) {
    useInputDraftStore().clearDraft(
      store.projectId ?? '',
      answeredNodeId,
      submittedRouteId,
    )
  }
}

/**
 * FAILED run reconciliation: canonical reads decide whether the Answer
 * persisted (→ repair affordance, never a second submission) or nothing
 * landed (→ explicit one-shot resubmit payload).
 */
export async function reconcileFailedAnswerRunAction(store: WorkspaceStore): Promise<void> {
  const reconciled = await store.refreshWorkspace()
  if (!reconciled) {
    store.answerOutcomeUnknown = true
    return
  }
  const answerId = store.findFinalizedAnswerForNode(store.pendingAnswerNodeId)
  if (answerId) {
    if (store.answerTargetRouteTip() === store.pendingAnswerNodeId) {
      store.repairableAnswerId = answerId
      store.resubmitAnswerPayload = null
      store.feedback = '回答已保存，后续生成未完成'
    } else {
      // The tip moved past the answered node: the mutation completed
      // despite the failure report. Never offer resubmit or repair.
      store.repairableAnswerId = null
      store.resubmitAnswerPayload = null
      store.pendingAnswerNodeId = null
      store.feedback = '回答已记录'
    }
  } else {
    store.resubmitAnswerPayload = store.lastSubmittedAnswerPayload
  }
}

/**
 * Reconciliation after the run could not be observed to a terminal state
 * (poll network loss beyond the budget). Canonical reads decide between
 * repair (Answer persisted), completed-anyway (tip advanced), and an
 * explicit unknown-outcome affordance. Never resubmits by itself.
 */
export async function reconcileUnknownAnswerOutcomeAction(store: WorkspaceStore): Promise<void> {
  const reconciled = await store.refreshWorkspace()
  if (!reconciled) {
    store.answerOutcomeUnknown = true
    return
  }
  const answerId = store.findFinalizedAnswerForNode(store.pendingAnswerNodeId)
  if (answerId) {
    store.answerOutcomeUnknown = false
    if (store.answerTargetRouteTip() === store.pendingAnswerNodeId) {
      store.repairableAnswerId = answerId
      store.resubmitAnswerPayload = null
      store.feedback = '回答已保存，后续生成未完成'
    } else {
      store.repairableAnswerId = null
      store.resubmitAnswerPayload = null
      store.pendingAnswerNodeId = null
      store.feedback = '回答已记录'
    }
  }
  // Without a persisted Answer the run may still be executing server
  // side: keep answerOutcomeUnknown so the user reconciles instead of
  // creating a second mutation.
}

/** Reconciles canonical state before allowing a failed submit to mutate again. */
export async function reconcileAnswerOutcomeAction(store: WorkspaceStore): Promise<boolean> {
  const payload = store.resubmitAnswerPayload
  if (!payload && !store.answerOutcomeUnknown) return false
  const previousError = store.error
  const reconciled = await store.refreshWorkspace()
  if (!reconciled) {
    store.answerOutcomeUnknown = true
    store.error = previousError
    return false
  }
  const answerId = store.findFinalizedAnswerForNode(store.pendingAnswerNodeId)
  store.answerOutcomeUnknown = false
  if (answerId) {
    if (store.answerTargetRouteTip() === store.pendingAnswerNodeId) {
      store.repairableAnswerId = answerId
      store.resubmitAnswerPayload = null
      store.feedback = '回答已保存，后续生成未完成'
    } else {
      store.repairableAnswerId = null
      store.resubmitAnswerPayload = null
      store.pendingAnswerNodeId = null
      store.feedback = '回答已记录'
    }
  } else {
    store.repairableAnswerId = null
    store.resubmitAnswerPayload = payload
  }
  store.error = previousError
  return true
}

/**
 * Repairs an existing answer checkpoint through a RESUME_ANSWER run. The
 * backend replays the original ANSWER_SUBMITTED semantics from the
 * persisted Answer, so this never creates a second Answer and the
 * frontend never re-sends its guessed copy of the user input.
 */
export async function repairAnswerForActiveFlowAction(
  store: WorkspaceStore,
  answerId: string,
): Promise<boolean> {
  if (!store.projectId || store.repairingAnswer || store.routeCommandPending) return false
  store.repairingAnswer = true
  store.error = null
  try {
    const run = await createAgentRun(store.projectId, {
      operation: 'RESUME_ANSWER',
      nodeId: store.activeState?.activeRoute?.tipNodeId ?? null,
      answerId,
    })
    store.answerRunId = run.runId
    store.answerRunPhase = run.phase
    useRunRegistryStore().register({
      runId: run.runId,
      operation: 'RESUME_ANSWER',
      routeId: store.activeState?.activeRoute?.id ?? null,
      sourceNodeId: store.activeState?.activeRoute?.tipNodeId ?? null,
    })
    await store.pollAnswerRun(run.runId)
    if (store.answerOutcomeUnknown) {
      store.error = toDisplayError(new ApiError(
        GENERIC_ERROR_MESSAGE, 'UNKNOWN_ERROR', 0))
      return false
    }
    store.feedback = '已重新请求后续生成'
    return true
  } catch (err) {
    const safeError = toDisplayError(err)
    const reconciled = await store.refreshWorkspace()
    if (reconciled) {
      store.repairableAnswerId = store.findFinalizedAnswerForActiveTip()
    }
    store.error = safeError
    return false
  } finally {
    store.repairingAnswer = false
  }
}

/** Re-submits only after reconciliation proved that the Answer was absent. */
export async function resubmitFailedAnswerAction(store: WorkspaceStore): Promise<boolean> {
  const payload = store.resubmitAnswerPayload
  if (!payload || !store.projectId || store.submitting || store.routeCommandPending) return false
  return store.submitAnswer(payload)
}

export function findFinalizedAnswerForActiveTipAction(store: WorkspaceStore): string | null {
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
  store: WorkspaceStore,
  nodeId: string | null,
): string | null {
  // The answer is looked up on the route it was submitted to. Under
  // multi-route work that route is NOT the Active route, so searching the
  // Active route's answers would report "nothing landed" and offer a
  // resubmit for an answer that already exists.
  const routeId = store.submittedRouteIdForCleanup ?? store.activeState?.activeRoute?.id ?? null
  if (!routeId || !nodeId) return null
  return store.graphView?.answers.find((answer) =>
    answer.routeId === routeId
    && answer.nodeId === nodeId
    && answer.inherited === false
    && answer.ownerRouteId === routeId,
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
export function answerTargetRouteTipAction(store: WorkspaceStore): string | null {
  const routeId = store.submittedRouteIdForCleanup
  if (!routeId) return null
  return store.graphView?.routes.find((route) => route.id === routeId)?.tipNodeId ?? null
}

export function findForkDraftRetryRouteIdAction(store: WorkspaceStore): string | null {
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
  store: WorkspaceStore,
  target: MutationFocusTarget | null,
): void {
  store.focusAfterMutation = target
}

export function consumeFocusAfterMutationAction(store: WorkspaceStore): MutationFocusTarget | null {
  const target = store.focusAfterMutation
  store.focusAfterMutation = null
  return target
}

export async function retryManualModelOperationAction(store: WorkspaceStore): Promise<boolean> {
  const intent = store.manualModelRetry
  if (!intent) return false
  if (intent.state === 'ambiguous') {
    const previousError = store.error
    await store.refreshWorkspace()
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

export async function retryForkDraftAction(store: WorkspaceStore): Promise<boolean> {
  const retryRouteId = store.forkDraftRetryRouteId
  if (!retryRouteId || store.routeCommandPending || store.drafting) {
    return false
  }
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
export async function retryPendingAgentRunAction(store: WorkspaceStore): Promise<boolean> {
  if (store.forkDraftRetryRouteId) return store.retryForkDraft()
  if (store.manualModelRetry?.kind === 'draft') {
    return store.retryManualModelOperation()
  }
  return store.draftQuestion()
}
