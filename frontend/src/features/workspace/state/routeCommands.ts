// 文件名:routeCommands.ts
// 用途:路线命令领域逻辑:显式路线生命周期命令(激活/恢复/归档/删除/fork/重答/换题)以及换题结果不可观测时的 fail-closed 对账,从 workspaceStore 拆出。
/*
 * 路线命令领域:显式路线生命周期命令(activate、restore、archive、delete、
 * fork、re-answer、regenerate),以及结果无法观测的 regenerate 的
 * fail-closed 对账。
 *
 * 每个函数都是从 `workspaceStore.ts` 原样搬出的 action 主体,只是把
 * `this` 换成了作为第一个参数传入的 store 实例。store 保留原 action 名
 * 并委托到这里,调用方零改动。
 */
import { createAgentRun } from '@/features/workspace/api/agentRuns'
import { toDisplayError } from '@/shared/http/displayError'
import { classifyModelFailure } from '@/shared/http/errorCopy'
import {
  activateRoute,
  archiveRoute,
  deleteRoute,
  forkNode,
  reanswerNode,
  restoreRoute,
} from '@/features/workspace/api/routes'
import type { RegenerateNodeRequest } from '@/shared/contracts/types'
import { useRunRegistryStore } from './runRegistryStore'
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
    // 只有持有会话的一方释放锁:过期命令的清理绝不能释放新会话的
    // route-command 锁(beginProject 在切换时已经重置过它)。
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

/*
 * 从历史节点 fork 一条新路线。运行时创建新路线 id 并把它设为活跃;前端
 * 随后刷新 canonical 读取,绝不猜测新路线 id。
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
  try {
    const result = await forkNode(projectId, nodeId, {
      sourceRouteId,
      label: label ?? null,
    })
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    // Fork 和首个子问题起草是两条独立的运行时命令。起草失败时有意保留
    // 新路线。
    store.routeCommandPending = false
    store.pendingRouteCommand = null
    const drafted = await store.draftQuestion()
    if (!isCurrent()) return false
    if (!drafted) {
      // 分支已创建但首个问题起草失败:不再写全局"重试起草"状态。起草
      // run 的失败由服务端未解决失败清单承接,在该路线下游的失败占位卡
      // 上重试;未形成 run 的提交失败可从路线菜单的正常"起草下一个问题"
      // 动作再次发起(绑定该路线,不回落 Active)。
      store.setFocusAfterMutation({
        routeId: result.route.id,
        nodeId: store.activeState?.activeRoute?.tipNodeId ?? result.route.tipNodeId,
      })
      store.feedback = '分支已创建，但首个后续问题起草失败'
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

/*
 * 确定性地重新生成一个历史节点。旧路线变为 SUPERSEDED,替代路线经运行时
 * 变为 OPEN + active;前端刷新 canonical 读取,而不是在本地重建这次流转。
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
  // 集成对话框提供了运行时契约要求的显式 sourceRouteId;这里不再合成
  // 兼容用的载荷。
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
      // 终态链叶子:替代路线现在是活跃路线;canonical 刷新负责所有
      // id——绝不在本地重建。RESPOND 叶子的消息优先于默认文案。
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
    // FAILED 或 unknown:通过共享的 fail-closed 对账刷新 canonical 读取
    // (轮询后才完成的流转会以一条全新的活跃替代路线出现)。
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
    // 创建 run 的请求本身失败;在任何重试入口之前先对账 canonical 读取。
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
