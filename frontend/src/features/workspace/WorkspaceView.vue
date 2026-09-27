<!--
  文件名:WorkspaceView.vue
  用途:工作台主页面("图优先"外壳):固定的 路线/画布/检查器 三区布局——
       左 RouteSidebar + 中央 GraphCanvas + 右 WorkspaceInspector,由 ResizableSidebar
       组合;运行时命令经 workspaceStore(仅 Active 路线),Focus/隐藏/侧栏等浏览器态
       在 graphUiStore;并承载恢复提示、提案卡、Spec Dock 与各类操作弹窗。
-->
<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { routerKey } from 'vue-router'
import ApiErrorBanner from '@/shared/ui/ApiErrorBanner.vue'
import AgentProposalCard from '@/features/workspace/components/AgentProposalCard.vue'
import RecoveryNotice from '@/features/workspace/components/RecoveryNotice.vue'
import SpecDock from '@/features/workspace/components/SpecDock.vue'
import ConfirmRouteActionDialog from '@/features/workspace/components/ConfirmRouteActionDialog.vue'
import RouteActionDialog from '@/features/workspace/components/RouteActionDialog.vue'
import ResourceDialog from '@/features/workspace/components/ResourceDialog.vue'
import RegenerateNodeDialog from '@/features/workspace/components/RegenerateNodeDialog.vue'
import GraphCanvas from '@/features/workspace/graph/components/GraphCanvas.vue'
import RelationProposalDialog from '@/features/workspace/graph/components/RelationProposalDialog.vue'
import ResizableSidebar from '@/features/workspace/components/ResizableSidebar.vue'
import RouteSidebar from '@/features/workspace/components/RouteSidebar.vue'
import WorkspaceInspector from '@/features/workspace/components/WorkspaceInspector.vue'
import {
  projectGraph,
  getVisibleRouteIds,
  type ContextualAiTarget,
  type GraphNodeRuntimeState,
  type GraphPendingProjection,
  type GraphRunProgress,
  type SpecAgentGraphNodeData,
} from '@/features/workspace/graph/graphProjection'
import { resolveReadingRouteId } from '@/features/workspace/graph/graphInteraction'
import {
  recoveryNoticeFromState,
  type RecoveryAction,
  type RecoveryNoticeModel,
} from '@/features/workspace/presentation/recoveryPresentation'
import { productErrorMessage, requiresModelSettings } from '@/shared/http/errorCopy'
import { useGraphUiStore } from '@/features/workspace/state/graphUiStore'
import { useRunRegistryStore, type RunRegistryEntry } from '@/features/workspace/state/runRegistryStore'
import { DRAFT_FAMILY_OPERATIONS, NODE_QUERY_OPERATION, SPEC_GENERATION_OPERATION, type UnresolvedFailure } from '@/features/workspace/api/agentRuns'
import NodeRecoveryBar, { type RecoveryItem } from '@/features/workspace/graph/components/NodeRecoveryBar.vue'
import { useWorkspaceStore } from '@/features/workspace/state/workspaceStore'
import type { SpecExportVariant } from '@/features/workspace/api/spec'
import type { RegenerateNodeRequest, SubmitAnswerRequest } from '@/shared/contracts/types'

/**
 * 图优先的工作台外壳,固定 路线/画布/检查器 三区。
 *
 * 布局:左 RouteSidebar + 中央 GraphCanvas + 右 WorkspaceInspector,
 * 由既有的 ResizableSidebar 基础设施组合而成。运行时命令经 workspaceStore
 * (只走 Active 路线);Focus/Dim/Hide/位置/侧栏 等浏览器态在 graphUiStore
 * (仅浏览器)。Focus 驱动共享节点的阅读;Active 始终只用于运行时。
 */
const props = defineProps<{ projectId: string }>()

const store = useWorkspaceStore()
const graphUi = useGraphUiStore()
const runRegistry = useRunRegistryStore()
const router = inject(routerKey, null)
const canvasRef = ref<InstanceType<typeof GraphCanvas> | null>(null)
const specDockRef = ref<InstanceType<typeof SpecDock> | null>(null)

const forkDialogOpen = ref(false)
const resourceDialogOpen = ref(false)
const reanswerDialogOpen = ref(false)
const regenerateDialogOpen = ref(false)
const confirmAction = ref<'archive' | null>(null)
const confirmRouteId = ref<string | null>(null)
const forkNodeId = ref<string | null>(null)
const regenerateNodeId = ref<string | null>(null)
const reanswerNodeId = ref<string | null>(null)

/**
 * 项目身份必须在 setup 阶段就建立，不能等 onMounted。
 *
 * 本组件与 WorkspaceInspector 里所有 `{ immediate: true }` 的 watcher 都在
 * setup 中执行（早于 onMounted）。若等到 onMounted 才切换身份，它们会用
 * **上一个项目**的 projectId / graphView 去发请求：上一个项目还在时只是
 * 一次脏读，已被删除时就是 404 PROJECT_NOT_FOUND，且错误会盖在新项目上。
 */
graphUi.initProject(props.projectId)
store.beginProject(props.projectId)

onMounted(() => {
  void store.loadWorkspace(props.projectId).then(() => {
    void store.refreshUndoRedoAvailability()
  })
  window.addEventListener('keydown', handleGlobalKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
})

/**
 * 全局撤销/重做快捷键（Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y）。输入框、下拉等
 * 可编辑元素聚焦时不劫持——文本编辑的原生撤销优先于图撤销。
 */
function handleGlobalKeydown(event: KeyboardEvent): void {
  if (!(event.ctrlKey || event.metaKey) || event.altKey) return
  const target = event.target as HTMLElement | null
  if (target
    && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA'
      || target.tagName === 'SELECT' || target.isContentEditable)) {
    return
  }
  const key = event.key.toLowerCase()
  if (key === 'z') {
    event.preventDefault()
    if (event.shiftKey) void store.redoGraph()
    else void store.undoGraph()
  } else if (key === 'y') {
    event.preventDefault()
    void store.redoGraph()
  }
}

// 每次 canonical 刷新后，浏览器视图状态与后端 graph 对齐。
watch(
  () => store.graphView,
  (view) => {
    graphUi.reconcile(view)
  },
)

const selectedNodeData = computed<SpecAgentGraphNodeData | null>(() => {
  if (!store.graphView || !graphUi.primarySelectedNodeId) {
    return null
  }
  const projection = projectGraph({
    view: store.graphView,
    activeNodeId: store.activeState?.activeNode?.id ?? null,
    uiState: {
      focusRouteId: graphUi.focusRouteId,
      lifecycleFilters: graphUi.lifecycleFilters,
      routeDisplayStates: graphUi.routeDisplayStates,
      isolatedRouteId: graphUi.isolatedRouteId,
      expandedNodeIds: graphUi.expandedNodeIds,
      showRelationLayer: graphUi.showRelationLayer,
    },
    savedPositions: graphUi.nodePositions,
  })
  return projection.nodes.find((node) => node.id === graphUi.primarySelectedNodeId)?.data ?? null
})

const selectedEdgeData = computed(() => {
  if (!graphUi.selectedEdgeId) return null
  if (graphUi.selectedEdgeId.startsWith('relation:')) {
    const relationId = graphUi.selectedEdgeId.slice('relation:'.length)
    const relation = store.graphView?.relations.find((entry) => entry.id === relationId) ?? null
    return {
      id: graphUi.selectedEdgeId,
      kind: 'relation' as const,
      relationType: relation?.relationType ?? null,
      routeIds: [] as string[],
    }
  }
  return {
    id: graphUi.selectedEdgeId,
    kind: graphUi.selectedEdgeId.startsWith('replacement:') ? 'replacement' as const : 'lineage' as const,
    relationType: null,
    routeIds: [...graphUi.selectedSharedEdgeRouteIds],
  }
})

const forkNodeData = computed(() =>
  forkNodeId.value
    ? store.graphView?.nodes.find((node) => node.id === forkNodeId.value) ?? null
    : null,
)

const regenerateNodeData = computed(() =>
  regenerateNodeId.value
    ? store.graphView?.nodes.find((node) => node.id === regenerateNodeId.value) ?? null
    : null,
)

const reanswerNodeData = computed(() =>
  reanswerNodeId.value
    ? store.graphView?.nodes.find((node) => node.id === reanswerNodeId.value) ?? null
    : null,
)

/**
 * 为节点命令(fork / 重新回答 / 重新生成)解析来源路线。
 *
 * 与画布投影使用同一个确定性解析器,因此绝不会出现节点用某条路线阅读、
 * 而它的命令却找不到来源路线的情况。
 * 只看这条路线 / 点卡片定下的 Focus 直接生效；当其它归属路线都被隐藏/筛掉时，
 * 唯一可见的那条也被视为已确定；只有真正歧义（多归属且都可见且无 Focus）时
 * 才返回 null，此时卡片会显式要求用户选一条。
 */
function sourceRouteForNode(nodeId: string | null) {
  if (!nodeId || !store.graphView) return null
  const memberships = store.graphView.routes.filter((route) => route.lineageNodeIds.includes(nodeId))
  const visible = getVisibleRouteIds(store.graphView, {
    lifecycleFilters: graphUi.lifecycleFilters,
    routeDisplayStates: graphUi.routeDisplayStates,
    isolatedRouteId: graphUi.isolatedRouteId,
  })
  const resolved = resolveReadingRouteId({
    membershipRouteIds: memberships.map((route) => route.id),
    visibleRouteIds: visible,
    focusRouteId: graphUi.focusRouteId,
  })
  return memberships.find((route) => route.id === resolved) ?? null
}

const forkSourceRoute = computed(() => sourceRouteForNode(forkNodeId.value))
const reanswerSourceRoute = computed(() => sourceRouteForNode(reanswerNodeId.value))
const regenerateSourceRoute = computed(() => sourceRouteForNode(regenerateNodeId.value))
const workspaceErrorMessage = computed(() =>
  productErrorMessage(store.error?.code ?? 'UNKNOWN_ERROR', store.error?.message),
)
/*
 * 通用错误条的重试标签(2026-09-27 起不再做全局恢复分发):旧实现按
 * 全局状态机派发"重新请求"(repairableAnswerId/manualModelRetry),
 * 与任务级恢复入口并行。现在错误条只保留三类无歧义出口——配置错误去
 * 设置页、可安全重提交的载荷、以及刷新/同步;失败任务的恢复一律从
 * 对应失败位置的任务级入口执行。
 */
const workspaceRetryLabel = computed(() => {
  if (store.error && requiresModelSettings(store.error.code)) return '前往模型设置'
  if (store.resubmitAnswerPayload) return '再次提交'
  return '刷新状态'
})
const workspaceRetrying = computed(() => store.loading || store.refreshing
  || store.submitting || store.repairingAnswer || store.drafting
  || store.routeCommandPending || store.generatingSpec)

/**
 * 产物关卡(artifact gate)会给出一个有限范围的恢复身份。
 * 到规范图里解析它,让用户看到真实的问题与归属路线,
 * 而不是一个不可读的 UUID,也不是"想当然地"按当前激活分支处理。
 */
const historicalAnswerRecoveryTarget = computed(() => {
  const error = store.error
  const details = error?.details
  const session = store.focusedAnswerSession
  const sessionRecovery = session?.historicalRecovery === true
    && session.status === 'REPAIRABLE'
    && session.routeId
    && session.nodeId
    && session.repairableAnswerId
  const answerId = error?.code === 'ANSWER_CYCLE_INCOMPLETE'
    && details?.answerId
    ? details.answerId
    : sessionRecovery ? session.repairableAnswerId : null
  const routeId = error?.code === 'ANSWER_CYCLE_INCOMPLETE'
    && details?.routeId
    ? details.routeId
    : sessionRecovery ? session.routeId : null
  const nodeId = error?.code === 'ANSWER_CYCLE_INCOMPLETE'
    && details?.nodeId
    ? details.nodeId
    : sessionRecovery ? session.nodeId : null
  if (!answerId || !routeId || !nodeId) {
    return null
  }
  const route = store.graphView?.routes.find((candidate) => candidate.id === routeId)
  const node = store.graphView?.nodes.find((candidate) => candidate.id === nodeId)
  return {
    answerId,
    routeId,
    nodeId,
    routeLabel: route?.label?.trim() || `路线 ${routeId.slice(0, 8)}`,
    question: node?.question?.trim() || '该历史回答',
  }
})

/**
 * 统一恢复提示：一次最多一个。model settings 错误仍走普通错误条；
 * 已被恢复模型覆盖的状态不再渲染旧的三块恢复按钮。
 */
const recoveryModel = computed<RecoveryNoticeModel | null>(() => {
  if (store.error && requiresModelSettings(store.error.code)) return null
  return recoveryNoticeFromState({
    answerOutcomeUnknown: store.answerOutcomeUnknown,
    resubmitAnswerPayload: store.resubmitAnswerPayload,
    // 只暴露对账态(needs_reconcile/ambiguous);'ready' 的全局"重新请求"
    // 入口已删除,由任务级恢复入口/正常业务动作承担。
    manualRetryState: store.manualModelRetry?.state === 'ready'
      ? null
      : store.manualModelRetry?.state ?? null,
    errorCode: store.error?.code ?? null,
    historicalAnswerRecovery: historicalAnswerRecoveryTarget.value,
  })
})

/**
 * 恢复提示与错误条各自独立渲染：恢复提示描述"待恢复的旧状态"（如可重试的
 * 失败运行），错误条描述"用户最新一次操作的失败"。历史行为是恢复提示独占
 * 状态层，导致用户后续任何操作失败（拖线被拒、路线命令 409 等）完全无反馈，
 * 表现为"点了没反应"。
 */
const showPlainErrorBanner = computed(() =>
  store.error !== null && !requiresModelSettings(store.error.code),
)

/** 待确认提案的节点上下文：来源问题的可读标题（截断）。 */
const confirmingProposalId = ref<string | null>(null)
const confirmableNodeContext = (inputNodeId: string | null): string | null => {
  if (!inputNodeId) return null
  const node = store.graphView?.nodes.find((candidate) => candidate.id === inputNodeId)
  const raw: unknown = node?.question ?? node?.content?.text
  const title = typeof raw === 'string' ? raw : null
  return title ? title.slice(0, 30) : null
}
async function handleAcceptConfirmable(proposalId: string): Promise<void> {
  confirmingProposalId.value = proposalId
  try {
    await store.acceptConfirmableProposal(proposalId)
  } finally {
    confirmingProposalId.value = null
  }
}
async function handleRejectConfirmable(proposalId: string): Promise<void> {
  confirmingProposalId.value = proposalId
  try {
    await store.rejectConfirmableProposal(proposalId)
  } finally {
    confirmingProposalId.value = null
  }
}

/**
 * tip 是"未回答问题"的路线集合：在这些路线上起草下一个问题注定被后端
 * UNANSWERED_QUESTION_HAS_CHILD 不变式拒绝，入口必须前置置灰（D1）。
 * 判定与后端一致：tip 存在、是问题节点、且该路线上没有它的已确认回答。
 */
const draftBlockedRouteIds = computed<string[]>(() => {
  const view = store.graphView
  if (!view) return []
  const answeredNodeIds = new Set(
    view.answers.map((answer) => `${answer.routeId}:${answer.nodeId}`),
  )
  return view.routes
    .filter((route) => {
      if (!route.tipNodeId) return false
      const tip = view.nodes.find((node) => node.id === route.tipNodeId)
      if (!tip || tip.kind !== 'INTERACTION') return false
      return !answeredNodeIds.has(`${route.id}:${route.tipNodeId}`)
    })
    .map((route) => route.id)
})

/** Registry 条目的白名单过程内容（summary + steps）。 */
function runProgressOf(entry: RunRegistryEntry | undefined): GraphRunProgress | null {
  if (!entry) return null
  return { summary: entry.summary, steps: entry.steps }
}

/**
 * 恢复链身份:同一 operation + 路线的起草/续跑状态属于同一条恢复链
 * (路线 tip 在任一时刻唯一,来源节点由路线决定),必须合并为唯一投影。
 * 刻意不把 sourceNodeId 纳入键:registry 重建的条目可能暂时缺失来源,
 * 缺失与已知来源必须是同一条链,否则去重失效。绝不按显示顺序/
 * Active/first/latest 猜测身份。
 */
function draftChainKey(routeId: string | null, operation: string | null | undefined): string {
  return `${operation ?? ''}::${routeId ?? '*'}`
}

/**
 * 画布 pending 卡列表:先按明确的任务/恢复链身份合并状态,再生成唯一
 * 投影(第二轮复核 R2-C 的闭合):
 * - legacy pendingRouteProjection(带失败文案等临时状态)优先生效;同一
 *   run 存在服务端失败身份时必须合并进同一张卡——否则失败卡没有恢复
 *   身份,按钮无法渲染;
 * - registry 条目与失败清单条目按 runId 与恢复链身份双重量去重,同一
 *   任务绝不产生两条投影;
 * - 恢复在途(retryRunId 存在)时旧失败卡原位转为重试进度,不与旧失败
 *   卡或新运行卡重复显示;
 * - 服务端未解决失败清单是硬刷新/重启后仍然存在的权威来源。
 */
const pendingProjections = computed<GraphPendingProjection[]>(() => {
  const projections: GraphPendingProjection[] = []
  const seen = new Set<string>()
  const chainDone = new Set<string>()
  const attach = (projection: GraphPendingProjection): void => {
    projections.push(projection)
    seen.add(projection.runId)
    chainDone.add(draftChainKey(projection.routeId, projection.operation))
  }

  const legacy = store.pendingRouteProjection
  if (legacy && legacy.status !== 'SUCCEEDED') {
    const legacyFailure = runRegistry.failures[legacy.runId] ?? null
    const legacyRun = runRegistry.runs[legacy.runId]
    // 过期 legacy 卡:其 run 已被服务端清单/注册表清除(被更新的失败接替)
    // 时绝不渲染,更不能占用恢复链槽位——否则新失败卡会因链去重而无按钮。
    if (legacyFailure || legacyRun) {
      let status = legacy.status
      let phase = legacy.phase
      let progress = runProgressOf(legacyRun)
      // 恢复在途:同一张卡原位转进度态,不另出新运行卡
      const retryRun = legacyFailure?.retryRunId
        ? runRegistry.runs[legacyFailure.retryRunId]
        : undefined
      if (retryRun && (retryRun.status === 'PENDING' || retryRun.status === 'RUNNING')) {
        status = retryRun.status
        phase = retryRun.phase
        progress = runProgressOf(retryRun)
      }
      attach({
        ...legacy,
        status,
        phase,
        progress,
        operation: 'DRAFT_QUESTION',
        failure: legacyFailure,
      })
    }
  }
  for (const entry of runRegistry.list) {
    if (seen.has(entry.runId)) continue
    if (entry.status === 'SUCCEEDED') continue
    if (entry.sourceNodeId && !(entry.status === 'FAILED' && DRAFT_FAMILY_OPERATIONS.has(entry.operation))) {
      continue // 绑定到既有节点：走 runtimeByNode 叠加
    }
    if (!entry.routeId) continue
    const chain = draftChainKey(entry.routeId, entry.operation)
    // 起草家族的条目(在途或失败)都渲染为占位卡:同一恢复链绝不出现第二张。
    if (DRAFT_FAMILY_OPERATIONS.has(entry.operation ?? '') && chainDone.has(chain)) continue
    attach({
      routeId: entry.routeId,
      sourceNodeId: entry.sourceNodeId,
      runId: entry.runId,
      status: entry.status,
      phase: entry.phase,
      message: null,
      operation: entry.operation,
      progress: runProgressOf(entry),
      failure: runRegistry.failures[entry.runId] ?? null,
    })
  }
  // 失败任务占位卡:起草/续跑家族的失败(下一节点尚未生成),来自服务端
  // 未解决清单——硬刷新/重启后依然存在。
  for (const failure of runRegistry.failureList) {
    if (failure.stale || !DRAFT_FAMILY_OPERATIONS.has(failure.operation)) continue
    if (!failure.routeId) continue
    const chain = draftChainKey(failure.routeId, failure.operation)
    // 恢复在途:原位转进度态——显示在途重试 run 的进度,绝不与旧失败
    // 卡或新运行卡重复显示。
    const retryRun = failure.retryRunId ? runRegistry.runs[failure.retryRunId] : undefined
    if (retryRun && (retryRun.status === 'PENDING' || retryRun.status === 'RUNNING')) {
      if (!seen.has(retryRun.runId) && !chainDone.has(chain)) {
        attach({
          routeId: failure.routeId,
          sourceNodeId: failure.sourceNodeId ?? retryRun.sourceNodeId,
          runId: retryRun.runId,
          status: retryRun.status,
          phase: retryRun.phase,
          message: null,
          operation: retryRun.operation || failure.operation,
          progress: runProgressOf(retryRun),
          failure,
        })
      }
      continue
    }
    if (seen.has(failure.runId) || chainDone.has(chain)) continue
    attach({
      routeId: failure.routeId,
      sourceNodeId: failure.sourceNodeId,
      runId: failure.runId,
      status: 'FAILED',
      phase: null,
      message: failure.reasonSummary ?? failure.actionLabel,
      operation: failure.operation,
      progress: null,
      failure,
    })
  }
  // 最终防线:同一 runId 与同一恢复链在输出中只允许出现一次——无论来源
  // (legacy 投影 / registry 条目 / 服务端失败清单)如何竞争,同一任务
  // 绝不产生两条可见投影。
  const out: GraphPendingProjection[] = []
  const outRuns = new Set<string>()
  const outChains = new Set<string>()
  for (const projection of projections) {
    const chain = draftChainKey(projection.routeId, projection.operation)
    if (outRuns.has(projection.runId) || outChains.has(chain)) continue
    outRuns.add(projection.runId)
    outChains.add(chain)
    out.push(projection)
  }
  return out
})

/** 既有节点上的运行时叠加：源节点已知的 in-flight run（回答/重生成/续修）。 */
/** 起草/续跑家族的失败不绑在源节点上:它们渲染为源节点下游的占位卡。 */

const runtimeByNode = computed<Record<string, GraphNodeRuntimeState>>(() => {
  const map: Record<string, GraphNodeRuntimeState> = {}
  // 属于失败恢复链的在途重试 run:进度显示在下游占位卡(原位转进度),
  // 不再在源节点上叠加第二份进度展示。
  const recoveryRetryRunIds = new Set(
    runRegistry.failureList
      .filter((failure) => failure.retryRunId)
      .map((failure) => failure.retryRunId as string),
  )
  for (const entry of runRegistry.list) {
    if (!entry.sourceNodeId || entry.status === 'SUCCEEDED') continue
    // 起草家族的失败走下游占位卡(见 pendingProjections),不在源节点上叠加。
    if (entry.status === 'FAILED' && DRAFT_FAMILY_OPERATIONS.has(entry.operation)) continue
    if (DRAFT_FAMILY_OPERATIONS.has(entry.operation) && recoveryRetryRunIds.has(entry.runId)) continue
    map[entry.sourceNodeId] = {
      status: entry.status,
      phase: entry.phase,
      progress: runProgressOf(entry),
    }
  }
  return map
})

/**
 * 任务级失败恢复:键 `${nodeId}::${routeId ?? '*'}`。共享节点上不同路线的
 * 失败互不覆盖;来源来自服务端判定的未解决失败清单(硬刷新/重启后仍在)。
 * 起草家族的失败走占位卡,不在这里出现。
 */
const recoveryByNode = computed<Record<string, { failures: UnresolvedFailure[] }>>(() => {
  const map: Record<string, { failures: UnresolvedFailure[] }> = {}
  for (const failure of runRegistry.failureList) {
    if (!failure.sourceNodeId || failure.stale) continue
    if (DRAFT_FAMILY_OPERATIONS.has(failure.operation)) continue
    const key = failure.sourceNodeId + '::' + (failure.routeId ?? '*')
    const bucket = map[key] ?? { failures: [] }
    bucket.failures.push(failure)
    map[key] = bucket
  }
  return map
})

/** 失败绑定路线的展示名(供恢复栏与顶部入口使用)。 */
function routeLabelOf(routeId: string | null): string | undefined {
  if (!routeId) return '无路线'
  return store.graphView?.routes.find((route) => route.id === routeId)?.label?.trim() || undefined
}

function recoveryItemsFor(failures: UnresolvedFailure[]): RecoveryItem[] {
  return failures.map((failure) => ({ failure, routeLabel: routeLabelOf(failure.routeId) }))
}

/** 任务级恢复入口:重试 / 前往模型设置 / 定位过期目标。 */
async function handleRetryFailure(failure: UnresolvedFailure): Promise<void> {
  if (failure.availableAction === 'GO_TO_MODEL_SETTINGS') {
    if (router) await router.push('/settings')
    return
  }
  if (failure.availableAction === 'STALE') {
    if (failure.sourceNodeId) graphUi.selectNode(failure.sourceNodeId)
    return
  }
  await store.retryFailedRun(failure)
}

/**
 * 把失败任务的定位目标解析成画布上的视觉实例 id。共享节点存在多个视觉
 * 实例,必须按失败绑定的路线解析,绝不回退 Active/first/latest。
 * 起草家族的失败目标是下游占位卡(pending:<runId>),不是源节点本身。
 */
function resolveFailureVisualNodeId(
  targetNodeId: string,
  routeId: string | null,
): string | null {
  if (!store.graphView) return null
  const projection = projectGraph({
    view: store.graphView,
    activeNodeId: store.activeState?.activeNode?.id ?? null,
    uiState: {
      focusRouteId: graphUi.focusRouteId,
      lifecycleFilters: graphUi.lifecycleFilters,
      routeDisplayStates: graphUi.routeDisplayStates,
      isolatedRouteId: graphUi.isolatedRouteId,
      expandedNodeIds: graphUi.expandedNodeIds,
      showRelationLayer: graphUi.showRelationLayer,
    },
    savedPositions: graphUi.nodePositions,
    pendings: pendingProjections.value,
  })
  const direct = projection.nodes.find((node) => node.id === targetNodeId)
  if (direct) return direct.id
  const instance = projection.nodes.find((node) =>
    node.data?.canonicalNodeId === targetNodeId
      && (!routeId || node.data?.routeIds.includes(routeId)),
  )
  return instance?.id ?? null
}

/**
 * 顶部恢复汇总的只读定位:按操作族把用户带到失败任务的真实位置——
 * 起草失败定位下游失败占位卡,规格失败打开并定位对应规格面板,查询失败
 * 打开对应节点和检查器,共享节点先切到失败绑定的路线视图。绝不发起
 * 重试/生成请求,绝不偷偷生成新路线;目标已删除或不在画布上时给出明确
 * 解释,不做静默回退。
 */
function handleLocateFailure(failure: UnresolvedFailure): void {
  const routeId = failure.routeId
  if (routeId && !store.graphView?.routes.some((route) => route.id === routeId)) {
    store.feedback = '该失败绑定的路线已被删除，原始位置无法定位；失败记录仍保留在清单中。'
    return
  }
  // 共享节点:先把阅读 Focus 切到失败绑定的路线,定位到的实例才是
  // 该失败任务自己的路线视图。
  if (routeId) graphUi.setFocusRoute(routeId)

  const draftFamily = DRAFT_FAMILY_OPERATIONS.has(failure.operation)
  const targetNodeId = draftFamily ? 'pending:' + failure.runId : failure.sourceNodeId
  if (!targetNodeId) {
    store.feedback = '该失败没有可定位的图上目标（来源节点不存在）。'
    return
  }
  const visualId = resolveFailureVisualNodeId(targetNodeId, routeId)
  if (!visualId) {
    store.feedback = '失败位置当前不在画布上（节点可能已被撤回或视图已变化）；可稍后重试定位。'
    return
  }
  // 起草失败选中的是占位卡本身,不是只选中源节点。
  graphUi.selectNode(visualId)
  if (failure.operation === SPEC_GENERATION_OPERATION) {
    specDockRef.value?.open()
  } else if (failure.operation === NODE_QUERY_OPERATION) {
    graphUi.setRightSidebar({ open: true, width: graphUi.rightSidebarWidth })
  }
  void nextTick(() => canvasRef.value?.locateNode(visualId))
}

/**
 * 中央一行状态已移除：过程展示收敛到画布节点内（pending 卡与既有节点的
 * runtime 叠加，见 pendingProjections / runtimeByNode），中央不再有第二个
 * 并行的过程展示面。终态反馈仍由 toast / Recovery / 横幅承担。
 */

const forkFinalizedRouteIds = computed(() => {
  if (!forkNodeId.value || !store.graphView) return []
  return store.graphView.answers
    .filter((answer) => answer.nodeId === forkNodeId.value)
    .map((answer) => answer.routeId)
})

/** Spec Dock 阅读路线：显式 Focus，单路线回退；绝不隐式改 Focus/Active。 */
const specReadingRouteId = computed<string | null>(() => {
  const focused = graphUi.readingRouteId()
  if (focused) return focused
  const routes = store.graphView?.routes ?? []
  return routes.length === 1 ? routes[0].id : null
})
const specReadingRouteLabel = computed(() => {
  if (!specReadingRouteId.value) return '未选择'
  return store.graphView?.routes.find((route) => route.id === specReadingRouteId.value)?.label?.trim() || '当前路线'
})
const specActiveRouteLabel = computed(() =>
  store.activeRoute?.label?.trim() || '当前路线',
)
/**
 * 规格面板的失败条目(R5-A 的闭合):以 API 契约 operation=GENERATE_ARTIFACT
 * 判定规格生成失败,并绑定面板正在查看的路线(显式阅读路线,与 specSnapshots
 * 同一来源)——Active 路线 A、阅读路线 B 时,面板只展示/恢复 B 的任务,
 * 绝不因 Active 指针串目标。
 */
const specFailureEntries = computed(() =>
  runRegistry.failureList.filter((failure) =>
    failure.operation === SPEC_GENERATION_OPERATION
    && failure.routeId !== null
    && failure.routeId === (specReadingRouteId.value ?? null)),
)
const specSnapshots = computed(() =>
  specReadingRouteId.value ? store.specsByRoute[specReadingRouteId.value] ?? [] : [],
)
const specSelectedId = computed(() =>
  specReadingRouteId.value ? store.selectedSpecIdByRoute[specReadingRouteId.value] ?? null : null,
)

// 读取路线变化时，规格历史从后端加载（与 Inspector 的需求加载同语义）。
// 只有当 store 已经属于当前项目时才读：这是一条显式不变量，任何一次
// 用别的项目的 id 发起的路线级读取都是 bug（旧项目被删就是 404）。
watch(
  specReadingRouteId,
  (routeId) => {
    if (routeId && store.projectId === props.projectId) {
      void store.loadRouteSpecs(routeId)
    }
  },
  { immediate: true },
)

async function handleGenerateSpec(): Promise<void> {
  const generated = await store.generateSpec()
  if (generated) {
    graphUi.setFocusRoute(store.activeRoute?.id ?? null)
  }
}

async function handleExportSpec(snapshotId: string, variant: SpecExportVariant): Promise<void> {
  await store.exportSpecMarkdown(snapshotId, variant)
}

function handleSelectSpec(snapshotId: string): void {
  if (specReadingRouteId.value) {
    store.selectSpecForRoute(specReadingRouteId.value, snapshotId)
  }
}

/**
 * Spec Dock 展开/折叠会改变 Graph 区域高度。等 Vue Flow 重新测量画布尺寸
 * 后，若当前 answerable node 被 Dock 顶部裁切，则只在 viewport 层把它带
 * 回可视区域 —— 绝不移动节点坐标或已保存位置。
 */
/**
 * Spec Dock 展开/折叠会改变 Graph 区域高度。Vue Flow 的 canvas 尺寸由
 * ResizeObserver 异步测量；等测量完成后，若当前 answerable node 被 Dock
 * 顶部裁切，则只在 viewport 层把它带回可视区域 —— 绝不移动节点坐标或已保存位置。
 *
 * 延迟定时器在组件卸载时必须清掉：否则它会跨越测试环境销毁继续执行
 * （jsdom 拆掉后 window/rAF 都不存在），变成 unhandled rejection。
 */
let specDockResizeTimer: number | null = null

function clearSpecDockResizeTimer(): void {
  if (specDockResizeTimer !== null) {
    window.clearTimeout(specDockResizeTimer)
    specDockResizeTimer = null
  }
}

function handleSpecDockExpandedChange(): void {
  clearSpecDockResizeTimer()
  specDockResizeTimer = window.setTimeout(() => {
    specDockResizeTimer = null
    void nextTick(() => {
      // jsdom(单元测试)没有 requestAnimationFrame;回退到定时器,
      // 保证回调在测试运行中不会变成未处理的 Promise 拒绝。
      const scheduleFrame = typeof window.requestAnimationFrame === 'function'
        ? window.requestAnimationFrame.bind(window)
        : (callback: FrameRequestCallback) => window.setTimeout(() => callback(0), 0)
      scheduleFrame(() => {
        const canvas = canvasRef.value as { ensureActiveNodeInView?: () => void } | null
        if (typeof canvas?.ensureActiveNodeInView === 'function') {
          canvas.ensureActiveNodeInView()
        }
      })
    })
  }, 160)
}

onUnmounted(clearSpecDockResizeTimer)

const reanswerFinalized = computed(() => {
  if (!reanswerNodeId.value || !reanswerSourceRoute.value || !store.graphView) return false
  return store.graphView.answers.some((answer) => answer.nodeId === reanswerNodeId.value
    && answer.routeId === reanswerSourceRoute.value!.id)
})

/**
 * 恢复 CTA 的语义意图 → 已有 store 命令,不新增语义。旧的全局分发已删除:
 * - 无锚点的 `repairableAnswerId` 回退(旧全局"继续生成")→ 任务级失败恢复;
 * - `retry-model-operation`(旧全局"重新请求")→ 各失败位置的任务级入口。
 * "同步状态"对 manual retry 意图执行对账(检查状态,绝不盲重试)。
 */
async function handleRecoveryAction(action: RecoveryAction): Promise<void> {
  if (action === 'reconcile-answer') {
    await store.reconcileAnswerOutcome()
  } else if (action === 'resume-answer') {
    const historical = historicalAnswerRecoveryTarget.value
    if (historical) {
      await store.repairAnswerForActiveFlow(
        historical.answerId, historical.routeId, historical.nodeId,
      )
    }
  } else if (action === 'resubmit-answer') {
    await store.resubmitFailedAnswer()
  } else if (store.manualModelRetry) {
    // 对账路径:needs_reconcile/ambiguous 只检查状态并收敛意图,不重发请求。
    await store.retryManualModelOperation()
  } else {
    await store.refreshWorkspace()
  }
}

/** 通用错误条的单一出口:配置错误/可验证锚点的恢复/刷新。失败任务的
 * 重试不在全局错误条分发——从对应失败位置的任务级入口执行。 */
async function retry(): Promise<void> {
  if (store.error && requiresModelSettings(store.error.code)) {
    if (router) await router.push({ name: 'settings' })
  } else if (store.answerOutcomeUnknown) {
    await store.reconcileAnswerOutcome()
  } else if (historicalAnswerRecoveryTarget.value) {
    // 历史 Answer 检查点:绑定具体 Answer/节点/路线(错误载荷携带身份)
    const historical = historicalAnswerRecoveryTarget.value!
    await store.repairAnswerForActiveFlow(
      historical.answerId, historical.routeId, historical.nodeId,
    )
  } else if (store.resubmitAnswerPayload) {
    await store.resubmitFailedAnswer()
  } else {
    await store.loadWorkspace(props.projectId)
  }
}

async function focusAfterMutation(): Promise<void> {
  const target = store.consumeFocusAfterMutation()
  const routeId = target?.routeId ?? store.activeState?.activeRoute?.id ?? null
  const nodeId = target?.nodeId ?? store.activeState?.activeNode?.id ?? null
  if (!routeId) return
  graphUi.setFocusRoute(routeId)
  await nextTick()
  if (nodeId) await canvasRef.value?.locateNode(nodeId)
}

async function handleDraft(): Promise<void> {
  await store.draftQuestion()
}

/** 显式路线的"起草下一个问题"：路线卡菜单 / 已回答末端节点 / 末端知识卡发起。 */
async function handleDraftNext(routeId: string): Promise<void> {
  await store.draftQuestion(routeId)
}

/** "+ 想法"：创建独立想法（不与任何节点连接），聚焦并直接进入编辑。 */
async function handleAddIdea(): Promise<void> {
  const nodeId = await store.createIdea()
  if (!nodeId) return
  await nextTick()
  graphUi.selectNode(nodeId)
  graphUi.requestNodeEdit(nodeId)
  await canvasRef.value?.locateNode(nodeId)
}

/**
 * 图上拖线 = Pending Relation Proposal。只打开确认器，不调用 backend；
 * 用户选择类型/方向并确认后才持久化。Cancel/Esc/click-away 保持 0 关系。
 */
function handleRelationProposal(payload: {
  sourceNodeId: string
  targetNodeId: string
}): void {
  graphUi.setPendingRelation(payload)
}

function relationNodeLabel(nodeId: string | null | undefined): string {
  if (!nodeId) return ''
  const node = store.graphView?.nodes.find((candidate) => candidate.id === nodeId)
  if (!node) return nodeId.slice(0, 8)
  if (node.question) return node.question.slice(0, 24)
  const text = node.content?.text
  return typeof text === 'string' && text ? text.slice(0, 24) : nodeId.slice(0, 8)
}

/**
 * 把画布上"浮动节点 ↔ 路线节点"的一条连线变成接入命令。
 *
 * 路线来源必须是**显式**的：先看该锚点属于哪些 OPEN 路线（锚点通常就是路线
 * 末端），再用同一套确定性解析（Focus / 只看这条路线 / 唯一可见归属）坍缩成
 * 单值。解析不出来就明确失败，绝不猜 Active/first/latest —— 与共享节点的
 * 来源路线规则保持一致，也避免把资源接到用户没在看的那条链上。
 */
async function handleConnectFloating(payload: {
  floatingNodeId: string
  anchorNodeId: string
}): Promise<void> {
  if (!store.graphView) return
  const view = store.graphView
  const anchorNode = view.nodes.find((node) => node.id === payload.anchorNodeId)
  const anchorLabel = anchorNode?.question?.slice(0, 24) ?? '该节点'
  // 后端只接受"当前末端"作为父节点，所以候选路线必须是该锚点为 tip 的路线。
  const candidates = view.routes.filter(
    (route) => route.lifecycleStatus === 'open' && route.tipNodeId === payload.anchorNodeId,
  )
  if (candidates.length === 0) {
    store.error = {
      code: 'CONNECT_REQUIRES_ROUTE_TIP',
      message: `只能接入「${anchorLabel}」所在路线的末端；请连到某条路线最末端的节点`,
    }
    return
  }
  const visible = getVisibleRouteIds(view, {
    lifecycleFilters: graphUi.lifecycleFilters,
    routeDisplayStates: graphUi.routeDisplayStates,
    isolatedRouteId: graphUi.isolatedRouteId,
  })
  const routeId = resolveReadingRouteId({
    membershipRouteIds: candidates.map((route) => route.id),
    visibleRouteIds: visible,
    focusRouteId: graphUi.focusRouteId,
  })
  const route = candidates.find((candidate) => candidate.id === routeId) ?? null
  if (!route) {
    store.error = {
      code: 'SOURCE_ROUTE_REQUIRED',
      message: '该节点是多条路线的末端：请先点击要接入的路线卡（或使用「只看这条路线」），再连线',
    }
    return
  }
  await store.connectFloatingNode(payload.floatingNodeId, route.id, payload.anchorNodeId)
}

async function handleRelationConfirm(payload: {
  sourceNodeId: string
  targetNodeId: string
  relationType: string
}): Promise<void> {
  // 先关 proposal(用户已确认方向/类型),再持久化;失败由 store.error 呈现。
  graphUi.clearPendingRelation()
  const ok = await store.createSemanticRelation(
    payload.sourceNodeId,
    payload.targetNodeId,
    payload.relationType as 'RELATED_TO' | 'DEPENDS_ON' | 'DERIVED_FROM' | 'CONFLICTS_WITH' | 'SUPPORTS',
  )
  if (ok) {
    // 端点保持选中状态,让刚创建的关系立即可见,
    // 无需再去开全局的"显示全部"开关。
    graphUi.selectNode(payload.sourceNodeId)
  }
}

/**
 * 添加资源 = 创建一个**独立**资源节点（零模型调用、不依赖 Active 路线）。
 * 归属路线由用户在画布上连线决定（见 handleConnectFloating）。
 */
async function handleCreateFloatingResource(
  subtype: 'TEXT' | 'URL' | 'FILE',
  content: Record<string, unknown>,
): Promise<void> {
  const ok = await store.createFloatingResource(subtype, content)
  if (ok) resourceDialogOpen.value = false
}

async function handleAnswer(payload: SubmitAnswerRequest): Promise<void> {
  await store.submitAnswer(payload)
}

function handleFork(nodeId: string): void {
  forkNodeId.value = nodeId
  forkDialogOpen.value = true
}

function handleReanswer(nodeId: string): void {
  reanswerNodeId.value = nodeId
  reanswerDialogOpen.value = true
}

/**
 * 断开接入：路线末端或挂在谱系下的出处节点都可断开（后端同规则校验）。
 * 归属路线沿 parentNodeId 向上解析（出处子节点不在 lineageNodeIds 父链上）；
 * 多条路线共享时要求先选定查看路线，绝不猜测。
 */
async function handleDisconnect(nodeId: string): Promise<void> {
  const view = store.graphView
  if (!view) return
  const node = view.nodes.find((candidate) => candidate.id === nodeId)
  const ancestors = new Set<string>()
  let parent = node?.parentNodeId ?? null
  while (parent != null && !ancestors.has(parent)) {
    ancestors.add(parent)
    parent = view.nodes.find((candidate) => candidate.id === parent)?.parentNodeId ?? null
  }
  const candidates = view.routes.filter(
    (route) => route.lifecycleStatus === 'open'
      && ((route.lineageNodeIds ?? []).includes(nodeId)
        || (route.lineageNodeIds ?? []).some((id) => ancestors.has(id))),
  )
  if (candidates.length === 0) {
    store.error = {
      code: 'DISCONNECT_REQUIRES_ROUTE_TIP',
      message: '只有已接入路线的节点可以断开',
    }
    return
  }
  if (candidates.length > 1) {
    store.error = {
      code: 'SOURCE_ROUTE_REQUIRED',
      message: '该节点属于多条路线：请先在「当前查看路线」中选择要断开的那条',
    }
    return
  }
  await store.disconnectNode(nodeId, candidates[0]!.id)
}

function handleRegenerate(nodeId: string): void {
  regenerateNodeId.value = nodeId
  regenerateDialogOpen.value = true
}

/**
 * 回答历史未答问题 = 激活其显式所属路线。用户必须先通过查看路线选择器
 * 选定一条明确的路线（graphUi.focusRouteId）；共享/多归属节点绝不回退到
 * Active/first/latest。激活是纯 route activation，不创建 RESUME 分支。
 */
async function handleActivateRouteForAnswer(routeId: string): Promise<void> {
  if (!routeId) {
    store.error = {
      code: 'SOURCE_ROUTE_REQUIRED',
      message: '请先选择明确的来源路线',
    }
    return
  }
  const ok = await store.activateRoute(routeId)
  if (ok) {
    await focusAfterMutation()
  }
}

function resolveContextualAiTarget(target: ContextualAiTarget): string | null {
  if (!store.graphView || !target.canonicalNodeId || !target.visualNodeKey) return null
  const projection = projectGraph({
    view: store.graphView,
    activeNodeId: store.activeState?.activeNode?.id ?? null,
    uiState: {
      focusRouteId: graphUi.focusRouteId,
      lifecycleFilters: graphUi.lifecycleFilters,
      routeDisplayStates: graphUi.routeDisplayStates,
      isolatedRouteId: graphUi.isolatedRouteId,
      expandedNodeIds: graphUi.expandedNodeIds,
      showRelationLayer: graphUi.showRelationLayer,
    },
    savedPositions: graphUi.nodePositions,
  })
  const targetNode = projection.nodes.find((node) =>
    node.id === target.visualNodeKey
      && node.data?.canonicalNodeId === target.canonicalNodeId,
  )
  return targetNode?.id ?? null
}

function handleContextualAi(target: ContextualAiTarget): void {
  const visualNodeKey = resolveContextualAiTarget(target)
  if (!visualNodeKey) return
  graphUi.selectNode(visualNodeKey)
  graphUi.setRightSidebar({ open: true, width: graphUi.rightSidebarWidth })
}

async function handleForkSubmit(label: string | null): Promise<void> {
  if (!forkNodeId.value) {
    return
  }
  const sourceRouteId = forkSourceRoute.value?.id
  if (!sourceRouteId) return
  const ok = await store.forkNode(forkNodeId.value, sourceRouteId, label)
  forkDialogOpen.value = false
  if (ok) {
    await focusAfterMutation()
    forkNodeId.value = null
  }
}

async function handleReanswerSubmit(label: string | null): Promise<void> {
  if (!reanswerNodeId.value) return
  const sourceRouteId = reanswerSourceRoute.value?.id
  if (!sourceRouteId) return
  const ok = await store.reanswerNode(reanswerNodeId.value, sourceRouteId, label)
  if (ok) {
    graphUi.setFocusRoute(store.activeState?.activeRoute?.id ?? null)
    await nextTick()
    await canvasRef.value?.locateNode(store.activeState?.activeNode?.id ?? '')
    reanswerDialogOpen.value = false
    reanswerNodeId.value = null
  }
}

/** Fork 的前置条件是显式的、弹窗内的本地操作。每条命令都在弹窗保持打开期间
 * 刷新规范状态;任何命令都不会被串成一次隐式的 Fork。 */
async function handleForkRestore(routeId: string): Promise<void> {
  await store.restoreRoute(routeId)
}

async function handleRegenerateSubmit(payload: RegenerateNodeRequest): Promise<void> {
  if (!regenerateNodeId.value) {
    return
  }
  const ok = await store.regenerateNode(regenerateNodeId.value, payload)
  regenerateDialogOpen.value = false
  if (ok) {
    await focusAfterMutation()
    regenerateNodeId.value = null
  }
}


function handleLocateRoute(routeId: string): void {
  void canvasRef.value?.locateRoute(routeId)
}

function openConfirm(kind: 'archive', routeId: string): void {
  confirmAction.value = kind
  confirmRouteId.value = routeId
}

/** 归档是唯一的"收起路线"动作（软删除入口已移除）；归档后默认从视图隐藏。 */
async function confirmDestructive(): Promise<void> {
  if (!confirmAction.value || !confirmRouteId.value) {
    return
  }
  const ok = await store.archiveRoute(confirmRouteId.value)
  if (ok) {
    confirmAction.value = null
    confirmRouteId.value = null
  }
}
</script>

<template>
  <div class="workspace-shell" data-test="workspace-shell">
    <div v-if="!store.loading" class="workspace-shell__body workspace-shell__body--fixed" data-test="workspace-center">
      <ResizableSidebar
        side="left"
        :open="graphUi.leftSidebarOpen"
        :width="graphUi.leftSidebarWidth"
        :min-width="220"
        :max-width="420"
        @update:open="graphUi.setLeftSidebar({ open: $event, width: graphUi.leftSidebarWidth })"
        @update:width="graphUi.setLeftSidebar({ open: graphUi.leftSidebarOpen, width: $event })"
      >
        <RouteSidebar
          :routes="store.graphView?.routes ?? []"
          :active-route-id="store.activeRoute?.id ?? null"
          :command-pending="store.routeCommandPending"
          :pending-route-command="store.pendingRouteCommand"
          :draft-blocked-route-ids="draftBlockedRouteIds"
          @locate-route="handleLocateRoute"
          @activate="store.activateRoute($event)"
          @restore="store.restoreRoute($event)"
          @archive="openConfirm('archive', $event)"
          @draft-next="handleDraftNext"
        />
      </ResizableSidebar>

      <div class="workspace-shell__center">
        <header class="workspace-shell__header" data-test="workspace-project-badge">
          <h1 class="workspace-shell__title">{{ store.project?.title ?? '工作区' }}</h1>
        </header>

        <div class="workspace-shell__status-layer">
          <RecoveryNotice
            v-if="recoveryModel"
            :model="recoveryModel"
            @action="handleRecoveryAction"
          />
          <ApiErrorBanner
            v-if="showPlainErrorBanner"
            :message="workspaceErrorMessage"
            :code="store.error!.code"
            :retry-label="workspaceRetryLabel"
            :retrying="workspaceRetrying"
            @retry="retry"
          />
          <!-- D2：回答/决策周期的"待确认提案"全局呈现面。没有它，策略层
               要求确认的意图变更对用户完全不可见，提案只能永远挂在库里。 -->
          <AgentProposalCard
            v-for="proposal in store.pendingConfirmableProposals"
            :key="proposal.proposalId"
            :action-family="proposal.actionFamily"
            :message="null"
            :node-context="confirmableNodeContext(proposal.inputNodeId)"
            :accepting="confirmingProposalId === proposal.proposalId"
            :rejecting="confirmingProposalId === proposal.proposalId"
            @accept="handleAcceptConfirmable(proposal.proposalId)"
            @reject="handleRejectConfirmable(proposal.proposalId)"
          />
        </div>

        <!-- 顶部统一入口:只读恢复汇总与定位(第四轮 R4-B 的闭合)。
             这里没有任何重试/生成控件——NodeRecoveryBar 的 locateOnly 模式
             真实不渲染恢复按钮;真正的恢复动作只存在于对应失败位置
             (节点恢复栏/下游占位卡/规格面板/节点检查器)。 -->
        <div
          v-if="store.graphView && runRegistry.failureList.length > 0"
          class="workspace-shell__pending-banner"
          data-test="pending-recovery-banner"
        >
          <NodeRecoveryBar
            locate-only
            :items="recoveryItemsFor(runRegistry.failureList)"
            :is-retrying="(id) => runRegistry.isRetrying(id)"
            @locate="handleLocateFailure"
          />
        </div>
        <div class="workspace-shell__graph-region">
          <GraphCanvas
            ref="canvasRef"
            class="workspace-shell__canvas"
            :view="store.graphView"
            :active-node-id="store.activeState?.activeNode?.id ?? null"
            :submitting="store.submitting"
            :drafting="store.drafting"
            :pending="store.routeCommandPending"
            :runtime-by-node="runtimeByNode"
            :recovery-by-node="recoveryByNode"
            :pendings="pendingProjections"
            @draft="handleDraft"
            @draft-next="handleDraftNext"
            @submit-answer="handleAnswer"
            @fork="handleFork"
            @reanswer="handleReanswer"
            @regenerate="handleRegenerate"
            @disconnect="handleDisconnect"
            @activate-route="handleActivateRouteForAnswer"
            @contextual-ai="handleContextualAi"
            @retry-failure="handleRetryFailure"
            @go-settings="router && router.push('/settings')"
            @add-idea="handleAddIdea"
            @add-resource="resourceDialogOpen = true"
            @relation-proposal="handleRelationProposal"
            @connect-floating="handleConnectFloating"
            @undo="store.undoGraph"
            @redo="store.redoGraph"
          />
          <div class="workspace-shell__toast-layer">
            <p v-if="store.refreshing" class="muted workspace-shell__refreshing" data-test="refreshing">
              正在刷新工作区…
            </p>
            <p v-if="store.feedback" class="feedback-line" data-test="feedback" role="status">{{ store.feedback }}</p>
          </div>
        </div>

        <SpecDock
          ref="specDockRef"
          :reading-route-id="specReadingRouteId"
          :reading-route-label="specReadingRouteLabel"
          :active-route-id="store.activeRoute?.id ?? null"
          :active-route-label="specActiveRouteLabel"
          :snapshots="specSnapshots"
          :selected-spec-id="specSelectedId"
          :generating="store.generatingSpec"
          :exporting="store.exportingSpec"
          :command-pending="store.routeCommandPending"
          :spec-failures="specFailureEntries"
          :is-retrying="(id) => runRegistry.isRetrying(id)"
          @retry-failure="handleRetryFailure"
          @generate-spec="handleGenerateSpec"
          @export-spec="handleExportSpec"
          @select-snapshot="handleSelectSpec"
          @expanded-change="handleSpecDockExpandedChange"
        />
      </div>

      <ResizableSidebar
        side="right"
        :open="graphUi.rightSidebarOpen"
        :width="graphUi.rightSidebarWidth"
        :min-width="300"
        :max-width="600"
        @update:open="graphUi.setRightSidebar({ open: $event, width: graphUi.rightSidebarWidth })"
        @update:width="graphUi.setRightSidebar({ open: graphUi.rightSidebarOpen, width: $event })"
      >
        <WorkspaceInspector
          :node-data="selectedNodeData"
          :selected-edge="selectedEdgeData"
          @fork="handleFork"
          @reanswer="handleReanswer"
          @regenerate="handleRegenerate"
        />
      </ResizableSidebar>
    </div>

    <p v-if="store.loading" class="muted workspace-shell__loading">正在加载工作区…</p>

    <ResourceDialog
      :open="resourceDialogOpen"
      :pending="store.graphCommandPending"
      :route-empty="(store.activeRoute?.tipNodeId ?? null) === null"
      @close="resourceDialogOpen = false"
      @submit="handleCreateFloatingResource"
    />

    <RouteActionDialog
      mode="fork"
      :open="forkDialogOpen"
      :node="forkNodeData"
      :source-route="forkSourceRoute"
      :pending="store.routeCommandPending"
      :finalized="forkSourceRoute ? forkFinalizedRouteIds.includes(forkSourceRoute.id) : false"
      @close="forkDialogOpen = false"
      @submit="handleForkSubmit"
      @restore-source="handleForkRestore"
    />

      <RegenerateNodeDialog
        :open="regenerateDialogOpen"
        :node="regenerateNodeData"
        :source-route-id="regenerateSourceRoute?.id ?? null"
      :pending="store.pendingRouteCommand === 'regenerate'"
      @close="regenerateDialogOpen = false"
      @submit="handleRegenerateSubmit"
      />

    <RouteActionDialog
      mode="reanswer"
      :open="reanswerDialogOpen"
      :node="reanswerNodeData"
      :source-route="reanswerSourceRoute"
      :pending="store.pendingRouteCommand === 'reanswer'"
      :finalized="reanswerFinalized"
      @close="reanswerDialogOpen = false"
      @submit="handleReanswerSubmit"
      @restore-source="store.restoreRoute($event)"
    />

    <ConfirmRouteActionDialog
      :open="confirmAction !== null && confirmRouteId !== null"
      :kind="confirmAction ?? 'archive'"
      :route-label="confirmRouteId ? store.graphView?.routes.find((r) => r.id === confirmRouteId)?.label ?? null : null"
      :pending="store.routeCommandPending"
      @cancel="confirmAction = null; confirmRouteId = null"
      @confirm="confirmDestructive"
    />

    <RelationProposalDialog
      :open="graphUi.pendingRelation !== null"
      :source-node-id="graphUi.pendingRelation?.sourceNodeId ?? null"
      :target-node-id="graphUi.pendingRelation?.targetNodeId ?? null"
      :source-label="relationNodeLabel(graphUi.pendingRelation?.sourceNodeId)"
      :target-label="relationNodeLabel(graphUi.pendingRelation?.targetNodeId)"
      :pending="store.graphCommandPending"
      @confirm="handleRelationConfirm"
      @cancel="graphUi.clearPendingRelation()"
    />

  </div>
</template>
