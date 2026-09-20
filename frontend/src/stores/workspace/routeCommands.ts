/**
 * Route command domain: the explicit route lifecycle commands (activate,
 * restore, archive, delete, fork, re-answer, regenerate) and the fail-closed
 * reconciliation of a regenerate whose outcome could not be observed.
 *
 * Every function is the verbatim action body lifted out of `workspaceStore.ts`
 * with `this` replaced by the store instance passed in as the first argument.
 * The store keeps the action names and delegates, so no caller changes.
 */
import { createAgentRun } from '@/api/agentRuns'
import { toDisplayError } from '@/api/displayError'
import { classifyModelFailure } from '@/api/errorCopy'
import {
  activateRoute,
  archiveRoute,
  deleteRoute,
  forkNode,
  reanswerNode,
  restoreRoute,
} from '@/api/routes'
import type { RegenerateNodeRequest } from '@/api/types'
import { useRunRegistryStore } from '@/stores/runRegistryStore'
import type { RouteCommandSlice } from './slices'
import type { ManualModelRetryIntent } from './types'
import { captureProjectSession, withAnswerableNodeHint } from './shared'

export async function activateRouteAction(
  store: RouteCommandSlice,
  routeId: string,
): Promise<boolean> {
  if (!store.projectId || store.routeCommandPending || store.submitting || store.drafting) {
    return false
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  store.routeCommandPending = true
  store.pendingRouteCommand = 'activate'
  store.error = null
  try {
    await activateRoute(projectId, routeId)
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    store.feedback = '已设为当前路线'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = withAnswerableNodeHint(
      toDisplayError(err),
      store.activeState?.activeNode?.question ?? null,
    )
    return false
  } finally {
    // Only the owning session releases the lock: a stale command's cleanup
    // must not release the NEW session's route-command lock (beginProject
    // has already reset it on switch).
    if (isCurrent()) {
      store.routeCommandPending = false
      store.pendingRouteCommand = null
    }
  }
}

export async function restoreRouteAction(
  store: RouteCommandSlice,
  routeId: string,
): Promise<boolean> {
  if (!store.projectId || store.routeCommandPending || store.submitting || store.drafting) {
    return false
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  store.routeCommandPending = true
  store.pendingRouteCommand = 'restore'
  store.error = null
  try {
    await restoreRoute(projectId, routeId)
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    // 后端 restoreRoute 会无条件把恢复的路线设为运行路线，这里如实告知，
    // 避免用户以为只是"取消归档"而不知道激活标记已被切换。
    store.feedback = '已恢复路线，并已设为运行路线'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    if (isCurrent()) {
      store.routeCommandPending = false
      store.pendingRouteCommand = null
    }
  }
}

export async function archiveRouteAction(
  store: RouteCommandSlice,
  routeId: string,
): Promise<boolean> {
  if (!store.projectId || store.routeCommandPending || store.submitting || store.drafting) {
    return false
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  store.routeCommandPending = true
  store.pendingRouteCommand = 'archive'
  store.error = null
  try {
    await archiveRoute(projectId, routeId)
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    store.feedback = '已归档路线'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    if (isCurrent()) {
      store.routeCommandPending = false
      store.pendingRouteCommand = null
    }
  }
}

export async function deleteRouteAction(
  store: RouteCommandSlice,
  routeId: string,
): Promise<boolean> {
  if (!store.projectId || store.routeCommandPending || store.submitting || store.drafting) {
    return false
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  store.routeCommandPending = true
  store.pendingRouteCommand = 'delete'
  store.error = null
  try {
    await deleteRoute(projectId, routeId)
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    store.feedback = '已删除路线'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    if (isCurrent()) {
      store.routeCommandPending = false
      store.pendingRouteCommand = null
    }
  }
}

/**
 * Forks a new route from a historical node. The runtime creates the new
 * route id and makes it active; the frontend then refreshes canonical
 * reads and never guesses the new route id.
 */
export async function forkNodeAction(
  store: RouteCommandSlice,
  nodeId: string,
  sourceRouteId: string,
  label?: string | null,
): Promise<boolean> {
  if (!store.projectId || store.routeCommandPending || store.submitting || store.drafting) {
    return false
  }
  if (!sourceRouteId) {
    store.error = { code: 'SOURCE_ROUTE_REQUIRED', message: '请选择明确的来源路线' }
    return false
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  store.routeCommandPending = true
  store.pendingRouteCommand = 'fork'
  store.error = null
  store.forkDraftRetryRouteId = null
  try {
    const result = await forkNode(projectId, nodeId, {
      sourceRouteId,
      label: label ?? null,
    })
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    // Fork and first-child Draft are separate Runtime commands. The
    // route is intentionally preserved if Draft fails.
    store.routeCommandPending = false
    store.pendingRouteCommand = null
    const drafted = await store.draftQuestion()
    if (!isCurrent()) return false
    if (!drafted) {
      store.forkDraftRetryRouteId = result.route.id
      store.setFocusAfterMutation({
        routeId: result.route.id,
        nodeId: store.activeState?.activeRoute?.tipNodeId ?? result.route.tipNodeId,
      })
      store.feedback = '分支已创建，但首个后续问题起草失败，可重试'
      return false
    }
    store.setFocusAfterMutation({
      routeId: result.route.id,
      nodeId: store.activeState?.activeRoute?.tipNodeId ?? result.route.tipNodeId,
    })
    store.feedback = '已创建新分支路线'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    if (isCurrent()) {
      store.routeCommandPending = false
      store.pendingRouteCommand = null
    }
  }
}

export async function reanswerNodeAction(
  store: RouteCommandSlice,
  nodeId: string,
  sourceRouteId: string,
  label?: string | null,
): Promise<boolean> {
  if (!store.projectId || store.routeCommandPending || store.submitting || store.drafting) {
    return false
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  store.routeCommandPending = true
  store.pendingRouteCommand = 'reanswer'
  store.error = null
  try {
    await reanswerNode(projectId, nodeId, { sourceRouteId, label: label ?? null })
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    store.feedback = '已创建重新回答路线'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    if (isCurrent()) {
      store.routeCommandPending = false
      store.pendingRouteCommand = null
    }
  }
}

/**
 * Deterministically regenerates a historical node. Old route becomes
 * SUPERSEDED and the replacement route becomes OPEN + active via the
 * runtime; the frontend refreshes canonical reads instead of
 * reconstructing the transition locally.
 */
export async function regenerateNodeAction(
  store: RouteCommandSlice,
  nodeId: string,
  payload: RegenerateNodeRequest,
): Promise<boolean> {
  if (!store.projectId || store.routeCommandPending || store.submitting || store.drafting) {
    return false
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  store.routeCommandPending = true
  store.pendingRouteCommand = 'regenerate'
  store.error = null
  const beforeRouteIds = store.graphView?.routes.map((route) => route.id) ?? []
  const beforeActiveRouteId = store.activeState?.activeRoute?.id ?? null
  // The integrated dialog supplies the explicit sourceRouteId required by
  // the Runtime contract; no compatibility payload is synthesized here.
  try {
    const run = await createAgentRun(projectId, {
      operation: 'REGENERATE_NODE',
      nodeId,
      sourceRouteId: payload.sourceRouteId,
      freeText: payload.instruction ?? null,
    })
    if (!isCurrent()) return false
    useRunRegistryStore().register({
      runId: run.runId,
      operation: 'REGENERATE_NODE',
      routeId: payload.sourceRouteId ?? null,
      sourceNodeId: nodeId,
    })
    const outcome = await store.pollRunChainToTerminal(run.runId)
    if (!isCurrent()) return false
    if (outcome !== 'unknown' && outcome !== 'failed') {
      // Terminal chain leaf: the replacement route is now the active
      // route; the canonical refresh owns every id — never reconstructed
      // locally. A RESPOND leaf message wins over the default copy.
      const replacementNodeId = outcome.producedNodeId
      await store.refreshWorkspace()
      if (!isCurrent()) return false
      store.feedback = outcome.respondMessage ?? '已创建换一个问题路线'
      store.manualModelRetry = null
      const focusRouteId = store.activeState?.activeRoute?.id
      if (focusRouteId) {
        store.setFocusAfterMutation({
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
      store.manualModelRetry = intent
      const recovered = await store.reconcileRegenerateRetry(intent)
      if (recovered) return true
      return false
    }
    store.error = {
      code: 'AGENT_RUN_FAILED',
      message: '换一个问法的运行失败，请重试',
    }
    store.manualModelRetry = intent
    return false
  } catch (err) {
    // Create-run request itself failed; reconcile canonical reads before
    // any retry affordance.
    if (!isCurrent()) return false
    const safeError = toDisplayError(err)
    store.error = safeError
    const disposition = classifyModelFailure(safeError.code, safeError.status)
    if (disposition === 'none') {
      store.manualModelRetry = null
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
    store.manualModelRetry = intent
    if (intent.state === 'needs_reconcile') {
      const recovered = await store.reconcileRegenerateRetry(intent)
      if (recovered) return true
    }
    return false
  } finally {
    if (isCurrent()) {
      store.routeCommandPending = false
      store.pendingRouteCommand = null
    }
  }
}

export async function reconcileRegenerateRetryAction(
  store: RouteCommandSlice,
  intent: Extract<ManualModelRetryIntent, { kind: 'regenerate' }>,
): Promise<boolean> {
  const { isCurrent } = captureProjectSession(store)
  const previousError = store.error
  const reconciled = await store.refreshWorkspace()
  if (!isCurrent()) return false
  if (!reconciled) {
    store.error = previousError
    return false
  }
  const afterRoutes = store.graphView?.routes ?? []
  const newRoutes = afterRoutes.filter((route) => !intent.beforeRouteIds.includes(route.id))
  const matchingRoutes = newRoutes.filter((route) =>
    route.branchType === 'regenerate'
    && route.sourceRouteId === intent.payload.sourceRouteId
    && route.branchAtNodeId === intent.nodeId
    && route.replacementOfNodeId === intent.nodeId,
  )
  const activeRouteId = store.activeState?.activeRoute?.id ?? null
  if (matchingRoutes.length === 1 && activeRouteId === matchingRoutes[0].id) {
    store.manualModelRetry = null
    store.error = null
    store.feedback = '已创建换一个问题路线'
    store.setFocusAfterMutation({
      routeId: matchingRoutes[0].id,
      nodeId: matchingRoutes[0].tipNodeId,
    })
    return true
  }
  if (matchingRoutes.length === 0 && activeRouteId === intent.beforeActiveRouteId) {
    store.manualModelRetry = { ...intent, state: 'ready' }
    store.error = previousError
    return false
  }
  store.manualModelRetry = { ...intent, state: 'ambiguous' }
  store.error = {
    code: 'RECOVERY_AMBIGUOUS',
    message: '请求结果无法安全确认，请刷新状态后人工核对',
  }
  return false
}
