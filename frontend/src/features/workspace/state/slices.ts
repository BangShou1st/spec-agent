// 文件名:slices.ts
// 用途:领域切片:对工作区 store 的窄化类型级视图,把跨域状态写入限制在类型层(DeepReadonly + @ts-expect-error 负向证明),每个切片分只读输入/本域可写状态/跨域命令三类成员。
/*
 * 领域切片:对工作区 store 的窄化、类型级视图。
 *
 * 每个领域模块拿到的都是其中一个切片,而不是完整的 `WorkspaceStore`。
 * 由于切片是结构化类型,真实 store 无需任何包装即可满足它——但访问任何
 * 未在此列出的成员都是编译错误,因此跨域状态写入在类型层受限,而不是靠
 * 注释约定。
 *
 * 每个切片中的成员分为三类,分别表达:
 *
 * 1. 只读输入(`ReadonlyPick`)——项目身份与 canonical 后端读取
 *    (`graphView`、`activeState` 等)。切片可以读它们(包括嵌套属性与
 *    数组条目),但重新赋值与嵌套修改都是编译错误:`DeepReadonly` 让每个
 *    嵌套对象与数组深度只读,而不只是顶层引用。
 * 2. 领域自有可写状态(`Pick`)——该领域设置、清除与对账的标志/缓存。
 *    "每个 UI 意图单一写者"在这里仍是评审约定;跨会话的归属由会话守卫
 *    在运行时强制,而非类型系统。
 * 3. 跨域命令——既定约定:跨域调用走 store action(`store.xxx()`),
 *    绝不直接导入兄弟领域模块(那会造成 import 环)。
 *
 * 负向的编译期证明位于 `state/__tests__/slicesReadonly.spec.ts`:每个被
 * 禁止的跨域写入都标了 `@ts-expect-error`,只读契约一旦回归 typecheck
 * 就会失败。
 *
 * 要往切片里加成员,必须在这里的分组注释中给出理由。这就是评审关口。
 */
import type { WorkspaceStore } from './workspaceStore'

/*
 * 值的深度只读视图。函数原样保留(mapped type 会破坏调用签名),数组变成
 * 元素只读的 readonly 数组,对象变成属性只读且属性类型同样深度只读的
 * 对象。
 */
export type DeepReadonly<T> =
  T extends (...args: never[]) => unknown ? T
    : T extends readonly (infer U)[] ? readonly DeepReadonly<U>[]
      : T extends object ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
        : T

/*
 * 从 S 中挑选成员并暴露为深度只读:成员不可重新赋值,嵌套对象/数组不可
 * 修改。
 */
type ReadonlyPick<S, K extends keyof S> = {
  readonly [P in K]: DeepReadonly<S[P]>
}

/*
 * 异步 run 编排领域(`workspaceRuns.ts`)可触碰的内容:
 *
 * - 只读输入:项目身份(异步开始时捕获,每次 await 后重新校验)与
 *   canonical 图 + Active 指针,用于路线末端/回答解析与显式"当前路线"
 *   语义(起草/重试/修复以用户正在阅读的路线为目标)。本领域绝不写它们。
 * - 回答 run 会话——本领域自有。
 * - run 编排标志——本领域设置与清除的单写者标志(drafting、repairing、
 *   投影、手动重试、fork 起草重试)。
 * - 全局 UI 反馈(`feedback`/`error`)——有意共享的表面;每次写入都必须
 *   有过期身份检查护航。
 */
export type AnswerRunSlice =
  ReadonlyPick<
    WorkspaceStore,
    // 只读输入:项目身份 + canonical 图/Active 指针
    'projectId'
    | 'projectSessionId'
    | 'project'
    | 'graphView'
    | 'activeState'
  > & Pick<
    WorkspaceStore,
    // 回答 run 会话(本领域自有)
    | 'answerRunSessions'
    | 'focusedAnswerSession'
    | 'submittedRouteIdForCleanup'
    | 'resubmitAnswerPayload'
    | 'answerRunsInFlight'
    | 'submitting'
    // run 编排标志(单写者)
    | 'drafting'
    | 'repairingAnswer'
    | 'pendingDraftRespondMessage'
    | 'manualModelRetry'
    | 'pendingRouteProjection'
    | 'routeCommandPending'
    // 共享 UI 反馈表面(仅在过期身份检查后写入)
    | 'feedback'
    | 'error'
    // 经 store 门面调用的跨域 action
    | 'refreshWorkspace'
    | 'draftQuestion'
    | 'markPendingRouteFailed'
    | 'updatePendingRouteProjection'
    | 'setFocusAfterMutation'
    | 'focusAfterMutation'
    | 'retryManualModelOperation'
    | 'submitAnswer'
    | 'pollAnswerRun'
    | 'pollDraftRun'
    | 'pollRunChainToTerminal'
    | 'finishSuccessfulAnswerRun'
    | 'reconcileFailedAnswerRun'
    | 'reconcileSpecRetry'
    | 'reconcileRegenerateRetry'
    | 'generateSpec'
    | 'regenerateNode'
    // 任务级失败恢复(服务端判定的未解决失败)
    | 'rebuildUnresolvedFailures'
    | 'retryFailedRun'
    // 节点查询重试成功后的检查器结果刷新(6-5,只读写入 nodeQuery)
    | 'nodeQuery'
    | 'refreshNodeQueryResult'
  >

/*
 * 提案(node query)领域:加载与轮询节点查询提案。
 * 自有 `nodeQuery`、两个持久化提案列表及其生命周期流转;身份与 canonical
 * action 辅助函数只读。
 */
export type ProposalSlice =
  ReadonlyPick<WorkspaceStore, 'projectId' | 'projectSessionId'> & Pick<
    WorkspaceStore,
    | 'nodeQuery'
    | 'nodeQueryProposals'
    | 'pendingConfirmableProposals'
    | 'nodeRouteIds'
    | 'loadNodeQueryProposals'
    | 'pollNodeQuery'
    | 'pollRunChainToTerminal'
    | 'refreshUndoRedoAvailability'
    | 'refreshWorkspace'
    | 'feedback'
    | 'error'
  >

/*
 * 资源/图命令领域:fork 路线与资源图命令。
 * canonical 图 + Active 路线是只读输入(路线归属与末端解析);本领域绝不
 * 在本地改写它们。
 */
export type ResourceSlice =
  ReadonlyPick<
    WorkspaceStore,
    'projectId' | 'projectSessionId' | 'graphView' | 'activeState' | 'activeRoute'
  > & Pick<
    WorkspaceStore,
    | 'graphCommandPending'
    | 'forkNode'
    | 'draftQuestion'
    | 'setFocusAfterMutation'
    | 'refreshUndoRedoAvailability'
    | 'refreshWorkspace'
    | 'feedback'
    | 'error'
  >

/*
 * Spec 停靠栏领域:需求状态/规格快照的生成、导出与逐路线规格状态。
 * 身份、Active 指针与 run 编排锁(`routeCommandPending`)是只读输入。
 * 本领域自有 `manualModelRetry` 的 spec 类条目(生成/对账负责写入与
 * 清除)、其逐路线缓存以及自身的加载/生成标志。
 */
export type SpecDockSlice =
  ReadonlyPick<
    WorkspaceStore,
    'projectId' | 'projectSessionId' | 'activeState' | 'routeCommandPending'
  > & Pick<
    WorkspaceStore,
    | 'specsByRoute'
    | 'selectedSpecIdByRoute'
    | 'requirementStatesByRoute'
    | 'loadingRequirementRouteId'
    | 'loadingSpecs'
    | 'generatingSpec'
    | 'exportingSpec'
    | 'manualModelRetry'
    | 'reconcileSpecRetry'
    | 'pollRunChainToTerminal'
    | 'refreshWorkspace'
    | 'feedback'
    | 'error'
  >

/*
 * 图撤销领域:撤销/重做可用性与图历史命令。
 * 只有自身的可用性缓存、锁标志与共享反馈表面可写。
 */
export type GraphUndoSlice =
  ReadonlyPick<WorkspaceStore, 'projectId' | 'projectSessionId'> & Pick<
    WorkspaceStore,
    | 'undoRedo'
    | 'graphCommandPending'
    | 'refreshUndoRedoAvailability'
    | 'refreshWorkspace'
    | 'feedback'
    | 'error'
  >

/*
 * 路线命令领域:路线生命周期命令(激活/关闭/…)、起草编排交接与
 * mutation 后的聚焦。canonical 图/Active 指针与回答 run 锁(`submitting`)
 * 是只读输入。写入 `drafting`/`manualModelRetry`,
 * 因为路线命令驱动 run;这些标志按约定保持"每个 UI 意图单一写者"。
 */
export type RouteCommandSlice =
  ReadonlyPick<
    WorkspaceStore,
    'projectId' | 'projectSessionId' | 'graphView' | 'activeState' | 'submitting'
  > & Pick<
    WorkspaceStore,
    | 'pendingRouteCommand'
    | 'routeCommandPending'
    | 'drafting'
    | 'manualModelRetry'
    | 'draftQuestion'
    | 'reconcileRegenerateRetry'
    | 'pollRunChainToTerminal'
    | 'refreshWorkspace'
    | 'setFocusAfterMutation'
    | 'feedback'
    | 'error'
  >

/*
 * 共享辅助函数(`captureProjectSession`)所需的最小身份视图。
 * 对每个消费方而言身份不可变。
 */
export type ProjectSessionSlice = ReadonlyPick<
  WorkspaceStore,
  'projectId' | 'projectSessionId'
>
