// 文件名:workspaceLoader.ts
// 用途:工作区加载领域:项目身份切换、镜像后端状态的 canonical 读取(beginProject / loadWorkspace / refreshWorkspace)以及由其重建的恢复入口;是各领域状态的重置与组装点。
/*
 * 工作区加载领域:项目身份、镜像后端状态的 canonical 工作区读取,以及
 * 由它们重建的恢复入口。
 *
 * 每个函数都是从 `workspaceStore.ts` 原样搬出的 action 主体,只是把
 * `this` 换成了作为第一个参数传入的 store 实例。store 保留原 action 名
 * 并委托到这里,调用方零改动。
 *
 * 项目会话身份:`beginProject` 递增 `store.projectSessionId`。每个异步
 * 读取在开始时捕获 `(projectSessionId, projectId)`,并在每次 await 之后、
 * 写入 store 状态之前重新校验——包括 catch 与 finally 路径。因此针对
 * 项目 A 的慢请求绝不能覆盖项目 B 的 canonical 状态、错误或标志,也绝不
 * 能释放 B 的 `loading` 标志。计数器(而不只是 projectId)还覆盖了
 * A→B→A 切换与乱序响应的同项目刷新。canonical 刷新另外做了串行化:在
 * 另一个刷新运行期间发起的刷新会排在它后面,绝不丢弃。
 *
 * 注意——有意不做切片:与其他领域模块不同,加载器就是重置并重建每个
 * 领域状态(回答会话、spec 状态、撤销、提案、投影…)的组装点。这里用
 * 窄切片只会把 `WorkspaceStore` 重新罗列一遍。它的宽度就是它的工作。
 */
import { listActiveRuns, listUnresolvedRuns } from '@/features/workspace/api/agentRuns'
import type { AgentRunView } from '@/features/workspace/api/agentRuns'
import { toDisplayError } from '@/shared/http/displayError'
import { getProjectGraph } from '@/features/workspace/api/graph'
import { getProject } from '@/features/projects/api/projects'
import { getRequirementState } from '@/features/workspace/api/requirementState'
import { getActiveState, listRoutes } from '@/features/workspace/api/workspace'
import { useRunRegistryStore } from './runRegistryStore'
import { finalizeRetryCompletionAction } from './workspaceRuns'
import type { WorkspaceStore } from './workspaceStore'
import {
  loadConfirmableProposalsSafely,
  loadNodeQueryProposalsSafely,
} from './shared'

/*
 * 刷新链的逐 store 尾部(WeakMap,使并发的 pinia 实例——例如测试——
 * 永远看不到彼此的队列)。
 *
 * 该链只串行化同一项目会话的刷新:`beginProject` 会剪断它,因此新会话的
 * 刷新绝不排在旧会话仍在途的慢请求后面。
 */
const refreshTails = new WeakMap<WorkspaceStore, Promise<boolean>>()

/*
 * 同步地建立项目身份。
 *
 * store 是单例,寿命超过工作区组件。当用户离开一个工作区进入另一个时,
 * 新组件的每个 `{ immediate: true }` watcher 会在 setup 期间运行——即在
 * `onMounted` 之前——否则会读到上一个项目的 `projectId` 与 `graphView`,
 * 发出跨项目读取(当上个项目已被删除时报 404 PROJECT_NOT_FOUND)。
 * 在 setup 期间(而非挂载期间)清除身份,让这种读取成为不可能。
 *
 * 锁与在途标志是重置而非保留:旧会话的忙碌标志属于那些受守卫的
 * `finally` 块永远不会释放它们的 action(它们先校验会话),因此保留
 * 设置会永久冻结新项目。回答 run 会话与 canonical 修复检查点属于旧的
 * 项目纪元,一并丢弃;它们的在途轮询循环会自行脱离并停止观察。
 */
export function beginProjectAction(store: WorkspaceStore, projectId: string): void {
  useRunRegistryStore().clear()
  store.projectSessionId += 1
  store.projectId = projectId
  // 剪断刷新队列:新会话的刷新绝不排在旧会话仍在途(或已排队)的慢刷新
  // 后面。
  refreshTails.delete(store)
  store.project = null
  store.routes = []
  store.activeState = null
  store.requirementState = null
  store.feedback = null
  store.error = null
  store.answerRunSessions = []
  store.manualModelRetry = null
  store.pendingRouteProjection = null
  store.pendingDraftRespondMessage = null
  store.focusAfterMutation = null
  store.graphView = null
  store.requirementStatesByRoute = {}
  store.loadingRequirementRouteId = null
  store.selectedSpecIdByRoute = {}
  store.specsByRoute = {}
  store.nodeQuery = null
  store.nodeQueryProposals = []
  store.pendingConfirmableProposals = []
  store.undoRedo = { canUndo: false, canRedo: false }
  // 旧会话的忙碌标志在这里重置:每个异步 action 的清理都会先校验会话
  // 再清自己的标志,所以不做这次重置,旧 action 就永远不会释放新项目的
  // UI。
  store.loading = false
  store.refreshing = false
  store.drafting = false
  store.repairingAnswer = false
  store.routeCommandPending = false
  store.pendingRouteCommand = null
  store.graphCommandPending = false
  store.generatingSpec = false
  store.exportingSpec = false
  store.loadingSpecs = false
}

export async function loadWorkspaceAction(store: WorkspaceStore, projectId: string): Promise<void> {
  store.beginProject(projectId)
  const session = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
  store.loading = true
  try {
    const [project, activeState, routes, requirementState, graphView, proposals, confirmable] = await Promise.all([
      getProject(projectId),
      getActiveState(projectId),
      listRoutes(projectId),
      getRequirementState(projectId),
      getProjectGraph(projectId),
      loadNodeQueryProposalsSafely(projectId),
      loadConfirmableProposalsSafely(projectId),
    ])
    // 过期加载守卫:更新的 beginProject(项目切换,或同一项目的第二次
    // 加载)现在拥有 store——丢弃这个响应。
    if (!isCurrent()) return
    store.project = project
    store.activeState = activeState
    store.routes = routes
    store.requirementState = requirementState
    store.graphView = graphView
    store.nodeQueryProposals = proposals ?? []
    store.pendingConfirmableProposals = confirmable ?? []
    store.requirementStatesByRoute = {}
    store.specsByRoute = {}
    store.selectedSpecIdByRoute = {}
    await store.rebuildRunRegistry()
    await store.rebuildUnresolvedFailures()
  } catch (err) {
    // 过期加载绝不能把它的错误浮出到新项目。
    if (!isCurrent()) return
    store.error = toDisplayError(err)
  } finally {
    // 过期加载也绝不能释放新加载的 `loading` 标志。
    if (isCurrent()) {
      store.loading = false
      // beginProject 重置了撤销/重做可用性;从后端重新读取,让"刷新状态"
      // 绝不留下过期的置灰按钮。
      await store.refreshUndoRedoAvailability()
    }
  }
}

/** 命令之后重新读取 canonical 的后端派生工作区视图。 */
export async function refreshWorkspaceAction(store: WorkspaceStore): Promise<boolean> {
  // 身份在调用时同步捕获——不是在排队的函数体开始时,后者可能是微任务
  // (或更晚,排在慢刷新后面),届时项目可能已经切换。排队的函数体在
  // 每次写入之前都对照这份身份校验。
  const projectId = store.projectId
  const session = store.projectSessionId
  // 串行化刷新:在另一个刷新运行期间发起的刷新排在它后面,因此必要的
  // 命令后刷新绝不会仅仅因为 `refreshing` 仍为 true 而被丢弃。
  const tail = (refreshTails.get(store) ?? Promise.resolve(true)).catch(() => false)
  const run = tail.then(() => runWorkspaceRefresh(store, projectId, session))
  refreshTails.set(store, run.then(
    () => true,
    () => false,
  ))
  return run
}

async function runWorkspaceRefresh(
  store: WorkspaceStore,
  projectId: string | null,
  session: number,
): Promise<boolean> {
  if (!projectId) return false
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
  // 过期的排队任务:在写入任何状态之前、发送请求之前退出。用户切到 B
  // 之后才到达队头的项目 A 排队刷新,绝不能清 B 的错误、翻转 B 的
  // `refreshing` 标志,或再为 A 发一次请求。
  if (!isCurrent()) return false
  store.refreshing = true
  store.error = null
  try {
    const [project, activeState, routes, requirementState, graphView, proposals, confirmable] = await Promise.all([
      getProject(projectId),
      getActiveState(projectId),
      listRoutes(projectId),
      getRequirementState(projectId),
      getProjectGraph(projectId),
      loadNodeQueryProposalsSafely(projectId),
      loadConfirmableProposalsSafely(projectId),
    ])
    // 过期刷新守卫:响应可能属于已被替换的项目纪元——绝不写进新纪元。
    if (!isCurrent()) return false
    store.project = project
    store.activeState = activeState
    store.routes = routes
    store.requirementState = requirementState
    store.graphView = graphView
    store.nodeQueryProposals = proposals ?? []
    store.pendingConfirmableProposals = confirmable ?? []
    // RequirementState 是派生数据,回答/补丁都会改变它:每次 canonical
    // 刷新都丢弃路线级缓存,让阅读 UI 从后端重新加载。
    store.requirementStatesByRoute = {}
    await store.rebuildRunRegistry()
    await store.rebuildUnresolvedFailures()
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // 只有持有者清标志:新会话的刷新在途时,一个落定的过期刷新绝不能
    // 释放它。
    if (isCurrent()) {
      store.refreshing = false
    }
  }
}

/*
 * 刷新/重载后的恢复入口重建说明:旧的 canonical 全局检查点
 * (repairableAnswerId / forkDraftRetryRouteId)已删除——失败恢复统一由
 * 服务端未解决失败清单(rebuildUnresolvedFailures)与各失败位置的任务级
 * 入口承担;回答会话级检查点(answerRunSessions)仍由 beginProject 保留
 * 语义,见 store 内相关字段。
 */

/** 把在途 run 注册表与后端活跃 run 列表对账,让 run 卡片在页面刷新后
 * 存活。这里的失败是非致命的:注册表只驱动进度展示。 */
export async function rebuildRunRegistryAction(store: WorkspaceStore): Promise<void> {
  const projectId = store.projectId
  const session = store.projectSessionId
  if (!projectId) return
  try {
    const views = await listActiveRuns(projectId)
    // 列表可能在项目切换之后才 resolve;`beginProject` 已为新项目清空
    // 注册表——绝不用旧项目的 run 重新填充。
    if (store.projectSessionId !== session || store.projectId !== projectId) return
    useRunRegistryStore().rebuild(views)
  } catch {
    // 进度展示是 best-effort;canonical 状态不受影响。
  }
}


/** 加载项目内服务端判定的未解决失败,重建节点/规格/检查器上的失败恢复
 * 入口。失败恢复与进度注册表一样是非致命展示面:加载失败不影响 canonical
 * 状态。硬刷新、切换项目后返回、后端重启都经由本对账找回恢复入口。 */
export async function rebuildUnresolvedFailuresAction(store: WorkspaceStore): Promise<void> {
  const projectId = store.projectId
  const session = store.projectSessionId
  if (!projectId) return
  try {
    const views = await listUnresolvedRuns(projectId)
    if (store.projectSessionId !== session || store.projectId !== projectId) return
    useRunRegistryStore().rebuildFailures(views)
    resumeInFlightRetryWatches(store, projectId, session, views)
  } catch {
    // 恢复入口是 best-effort;canonical 状态不受影响。
  }
}

/**
 * 在途重试的对账续接(6-2/6-5):硬刷新或重进项目后,服务端清单里"携带
 * retryRunId 的失败"对应一个没有任何前端轮询者的在途重试 run——若不
 * 主动续接观察,它会永远停留在"重试中"。这里为每个未观察的在途重试
 * 挂一个链路轮询,并在 run 到达终态后走与主动重试完全相同的共享收尾
 * (finalizeRetryCompletionAction:canonical 刷新 + 失败清单对账)——
 * 第三轮复核 R3-B 的闭合:成功后新产物自动可见、恢复提示消失,不依赖
 * 发起重试的旧页面还活着;失败/unknown 由服务端清单的最新失败接替。
 * 跨项目切换时旧观察自然过期(会话身份检查)。
 */
const watchedRetryRuns = new Set<string>()

function resumeInFlightRetryWatches(
  store: WorkspaceStore,
  projectId: string,
  session: number,
  views: Awaited<ReturnType<typeof listUnresolvedRuns>>,
): void {
  for (const view of views) {
    const retryRunId = view.retryRunId
    if (!retryRunId || watchedRetryRuns.has(retryRunId)) continue
    if (view.retryStatus === 'completed' || view.retryStatus === 'failed') continue
    watchedRetryRuns.add(retryRunId)
    void (async () => {
      let outcome: AgentRunView | 'failed' | 'unknown' = 'unknown'
      try {
        outcome = await store.pollRunChainToTerminal(retryRunId)
      } finally {
        watchedRetryRuns.delete(retryRunId)
      }
      // run 已终态:与主动重试共用完成路径(会话过期保护在共享收尾内)。
      if (store.projectSessionId === session && store.projectId === projectId) {
        await finalizeRetryCompletionAction(store, view, retryRunId, outcome)
      }
    })()
  }
}

/** 从图读取解析 canonical 节点的路线归属。 */
export function nodeRouteIdsAction(store: WorkspaceStore, nodeId: string): string[] {
  const routes = store.graphView?.routes ?? []
  const ids = new Set<string>()
  for (const route of routes) {
    if (route.lineageNodeIds.includes(nodeId)) ids.add(route.id)
  }
  return [...ids]
}
