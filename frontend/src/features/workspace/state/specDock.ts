// 文件名:specDock.ts
// 用途:Spec 停靠栏领域逻辑:逐路线的需求状态与规格快照读取、规格生成/导出,以及生成结果不可观测时的 fail-closed 对账,从 workspaceStore 拆出。
/*
 * Spec 停靠栏领域:路线范围的需求状态与规格快照读取、规格生成/导出,
 * 以及结果无法观测的生成的 fail-closed 对账。
 *
 * 每个函数都是从 `workspaceStore.ts` 原样搬出的 action 主体,只是把
 * `this` 换成了作为第一个参数传入的 store 实例。store 保留原 action 名
 * 并委托到这里,调用方零改动。
 */
import { createAgentRun } from '@/features/workspace/api/agentRuns'
import { toDisplayError } from '@/shared/http/displayError'
import { classifyModelFailure } from '@/shared/http/errorCopy'
import { getRouteRequirementState } from '@/features/workspace/api/requirementState'
import { downloadSpecMarkdown, listRouteSpecs } from '@/features/workspace/api/spec'
import type { SpecExportVariant } from '@/features/workspace/api/spec'
import type { RequirementStateView, SpecSnapshotResponse } from '@/shared/contracts/types'
import { useRunRegistryStore } from './runRegistryStore'
import type { SpecDockSlice } from './slices'
import type { ManualModelRetryIntent } from './types'
import { captureProjectSession } from './shared'

/*
 * 逐 store 的请求代际计数器,用于路线范围的读取。
 *
 * 会话身份无法解决同一个项目内部的归属:对同一路线的两次重叠读取,绝不能
 * 让更旧的响应覆盖更新的那个,或释放更新请求的 loading 标记。每条路线的
 * 最新代际拥有缓存写入、错误写入与标记释放。
 */
const requirementLoadGenerations = new WeakMap<object, Map<string, number>>()
const specListGenerations = new WeakMap<object, Map<string, number>>()
/** 单槽 `loadingSpecs` 标志的单调令牌。 */
const specListFlagTokens = new WeakMap<object, { token: number }>()

/*
 * 加载(并缓存)某条显式路线的需求状态。缓存按路线 id 索引;没有任何
 * 全局选择决定归属。响应在写缓存前与项目会话校验:一次慢的读取绝不能
 * 污染更新项目纪元的缓存。
 */
export async function ensureRequirementStateAction(
  store: SpecDockSlice,
  routeId: string,
): Promise<RequirementStateView | null> {
  if (!store.projectId) {
    return null
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  const cached = store.requirementStatesByRoute[routeId]
  if (cached) {
    return cached
  }
  // 请求身份:本路线的最新代际决定谁能写缓存、错误与 loading 标记。
  const generations = requirementLoadGenerations.get(store) ?? new Map<string, number>()
  requirementLoadGenerations.set(store, generations)
  const myGeneration = (generations.get(routeId) ?? 0) + 1
  generations.set(routeId, myGeneration)
  const isLatestForRoute = (): boolean => generations.get(routeId) === myGeneration
  store.loadingRequirementRouteId = routeId
  try {
    const state = await getRouteRequirementState(projectId, routeId)
    if (!isCurrent() || !isLatestForRoute()) return state
    store.requirementStatesByRoute = {
      ...store.requirementStatesByRoute,
      [routeId]: state,
    }
    return state
  } catch (err) {
    if (!isCurrent() || !isLatestForRoute()) return null
    store.error = toDisplayError(err)
    return null
  } finally {
    // 只有会话仍然有效、且没有同路线(或其它路线)的更新请求持有该标记
    // 时,才释放它。
    if (
      isCurrent()
      && isLatestForRoute()
      && store.loadingRequirementRouteId === routeId
    ) {
      store.loadingRequirementRouteId = null
    }
  }
}

/** 为一条显式路线选择展示的规格快照。 */
export function selectSpecForRouteAction(
  store: SpecDockSlice,
  routeId: string,
  snapshotId: string | null,
): void {
  store.selectedSpecIdByRoute = {
    ...store.selectedSpecIdByRoute,
    [routeId]: snapshotId,
  }
}

/** 从后端加载某条路线的快照列表。 */
export async function loadRouteSpecsAction(store: SpecDockSlice, routeId: string): Promise<void> {
  if (!store.projectId) {
    return
  }
  const { projectId, isCurrent } = captureProjectSession(store)
  const generations = specListGenerations.get(store) ?? new Map<string, number>()
  specListGenerations.set(store, generations)
  const myGeneration = (generations.get(routeId) ?? 0) + 1
  generations.set(routeId, myGeneration)
  const isLatestForRoute = (): boolean => generations.get(routeId) === myGeneration
  const flags = specListFlagTokens.get(store) ?? { token: 0 }
  specListFlagTokens.set(store, flags)
  flags.token += 1
  const myToken = flags.token
  store.loadingSpecs = true
  try {
    const specs = await listRouteSpecs(projectId, routeId)
    if (!isCurrent() || !isLatestForRoute()) return
    store.specsByRoute = { ...store.specsByRoute, [routeId]: specs }
  } catch (err) {
    if (!isCurrent() || !isLatestForRoute()) return
    store.error = toDisplayError(err)
  } finally {
    // 会话守卫:过期加载的清理绝不能释放新会话的标志(beginProject 已在
    // 切换时重置)。令牌守卫:同一会话内,较早的并发加载绝不能释放较晚
    // 加载的标志。
    if (isCurrent() && flags.token === myToken) {
      store.loadingSpecs = false
    }
  }
}

/*
 * 经后端为 Active 路线生成一份规格快照。成功后重新加载 canonical 快照
 * 列表,并把新快照选入该路线的缓存;前端绝不在本地合成规格,也绝不在此
 * 设置 Focus。返回新快照是否落到了这条路线上。
 */
export async function generateSpecAction(store: SpecDockSlice): Promise<boolean> {
  if (!store.projectId || store.generatingSpec || store.routeCommandPending) {
    return false
  }
  const activeRoute = store.activeState?.activeRoute
  if (!activeRoute || !activeRoute.tipNodeId) {
    store.error = {
      code: 'NO_ACTIVE_TIP_NODE',
      message: 'The active route has no tip node to generate a spec from.',
    }
    return false
  }
  store.generatingSpec = true
  store.error = null
  const routeId = activeRoute.id
  const { projectId, isCurrent } = captureProjectSession(store)
  // 单个 try/catch/finally 覆盖整个生成流程——包括基线读取。基线失败也
  // 会经过这个 finally 返回,生成锁对持有会话总是被释放,同一会话的后续
  // 代际也绝不会被残留阻塞。
  let baselineSpecs: SpecSnapshotResponse[]
  let beforeSpecIds: string[] = []
  try {
    try {
      // 这次读取是 mutation 的基线。它失败时,绝不去启动一个结果无法
      // 再对账的生成请求。
      baselineSpecs = await listRouteSpecs(projectId, routeId)
    } catch (err) {
      if (!isCurrent()) return false
      store.error = toDisplayError(err)
      store.manualModelRetry = null
      return false
    }
    if (!isCurrent()) return false
    store.specsByRoute = { ...store.specsByRoute, [routeId]: baselineSpecs }
    beforeSpecIds = baselineSpecs.map((snapshot) => snapshot.id)
    const created = await createAgentRun(projectId, {
      operation: 'GENERATE_ARTIFACT',
    })
    if (!isCurrent()) return false
    useRunRegistryStore().register({
      runId: created.runId,
      operation: 'GENERATE_ARTIFACT',
      routeId,
      sourceNodeId: activeRoute.tipNodeId ?? null,
    })
    const outcome = await store.pollRunChainToTerminal(created.runId)
    if (!isCurrent()) return false
    if (outcome === 'unknown' || outcome === 'failed') {
      // FAILED 或结果未知:经共享的 fail-closed 对账刷新 canonical 读取
      // (恰有一条新快照规则)。
      const intent: Extract<ManualModelRetryIntent, { kind: 'spec' }> = {
        kind: 'spec',
        routeId,
        beforeSpecIds,
        state: outcome === 'failed' ? 'ready' : 'needs_reconcile',
      }
      if (outcome === 'unknown') {
        store.manualModelRetry = intent
        const recovered = await store.reconcileSpecRetry(intent)
        if (recovered) return true
        return false
      }
      store.error = {
        code: 'AGENT_RUN_FAILED',
        message: '生成规格快照的运行失败，请重试',
      }
      store.manualModelRetry = intent
      return false
    }
    // COMPLETED:从 canonical 后端列表中选出产出的快照——绝不在本地拼装。
    const producedId = outcome.producedSpecSnapshotId
    const specs = await listRouteSpecs(projectId, routeId)
    if (!isCurrent()) return false
    store.specsByRoute = { ...store.specsByRoute, [routeId]: specs }
    const produced = specs.find((snapshot) => snapshot.id === producedId)
    if (!produced) {
      store.error = {
        code: 'SPEC_SNAPSHOT_NOT_FOUND',
        message: '生成的规格快照无法读取',
      }
      return false
    }
    store.selectedSpecIdByRoute = {
      ...store.selectedSpecIdByRoute,
      [routeId]: produced.id,
    }
    store.feedback = '已生成规格快照'
    store.manualModelRetry = null
    return true
  } catch (err) {
    // 创建 run 的请求本身失败或结果未知;在任何重试入口之前先对账
    // canonical 读取。
    if (!isCurrent()) return false
    const safeError = toDisplayError(err)
    store.error = safeError
    const disposition = classifyModelFailure(safeError.code, safeError.status)
    if (disposition === 'none') {
      store.manualModelRetry = null
      return false
    }
    const intent: Extract<ManualModelRetryIntent, { kind: 'spec' }> = {
      kind: 'spec',
      routeId,
      beforeSpecIds,
      state: disposition === 'unknown' ? 'needs_reconcile' : 'ready',
    }
    store.manualModelRetry = intent
    if (intent.state === 'needs_reconcile') {
      const recovered = await store.reconcileSpecRetry(intent)
      if (recovered) return true
    }
    return false
  } finally {
    // 只有持有会话的一方释放生成锁:过期生成的清理绝不能释放新会话的
    // 标志。
    if (isCurrent()) {
      store.generatingSpec = false
    }
  }
}

/*
 * 下载一份快照的 Markdown 导出(浏览器保存)。后端确定性地渲染已存储的
 * 快照;本 action 只负责下载前后的 pending/错误/反馈状态。
 */
export async function exportSpecMarkdownAction(
  store: SpecDockSlice,
  snapshotId: string,
  variant: SpecExportVariant,
): Promise<boolean> {
  if (store.exportingSpec) return false
  const { isCurrent } = captureProjectSession(store)
  store.exportingSpec = true
  store.error = null
  try {
    await downloadSpecMarkdown(snapshotId, variant)
    if (!isCurrent()) return false
    store.feedback =
      variant === 'delivery' ? '已导出开发需求文档' : '已导出规格快照 Markdown'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // 只有持有会话的一方释放标志;beginProject 在切换时重置它,新项目
    // 绝不会被旧导出冻结。
    if (isCurrent()) {
      store.exportingSpec = false
    }
  }
}

export async function reconcileSpecRetryAction(
  store: SpecDockSlice,
  intent: Extract<ManualModelRetryIntent, { kind: 'spec' }>,
): Promise<boolean> {
  const { projectId, isCurrent } = captureProjectSession(store)
  const previousError = store.error
  const reconciled = await store.refreshWorkspace()
  if (!isCurrent()) return false
  if (!reconciled) {
    store.error = previousError
    return false
  }
  if (store.activeState?.activeRoute?.id !== intent.routeId) {
    store.manualModelRetry = { ...intent, state: 'ambiguous' }
    store.error = {
      code: 'RECOVERY_AMBIGUOUS',
      message: '请求结果无法安全确认，请刷新状态后人工核对',
    }
    return false
  }
  let specs: SpecSnapshotResponse[]
  try {
    specs = await listRouteSpecs(projectId, intent.routeId)
  } catch {
    if (!isCurrent()) return false
    store.error = previousError
    return false
  }
  if (!isCurrent()) return false
  store.specsByRoute = { ...store.specsByRoute, [intent.routeId]: specs }
  const newSpecs = specs.filter((snapshot) => !intent.beforeSpecIds.includes(snapshot.id))
  if (newSpecs.length === 1) {
    store.selectedSpecIdByRoute = {
      ...store.selectedSpecIdByRoute,
      [intent.routeId]: newSpecs[0].id,
    }
    store.manualModelRetry = null
    store.error = null
    store.feedback = '已生成规格快照'
    return true
  }
  if (newSpecs.length === 0) {
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
